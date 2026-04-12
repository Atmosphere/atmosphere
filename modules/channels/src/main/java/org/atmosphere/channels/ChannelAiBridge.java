/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.channels;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automatically bridges external messaging channels to the AI backend.
 * <p>
 * When {@code atmosphere-channels} and {@code atmosphere-ai} are both on the
 * classpath, this bridge routes incoming channel messages to the configured
 * LLM and sends the response back through the originating platform.
 * <p>
 * Zero code required — just add both dependencies and configure credentials.
 */
public class ChannelAiBridge {

    private static final Logger logger = LoggerFactory.getLogger(ChannelAiBridge.class);
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant. Keep responses concise and friendly. "
            + "Format responses appropriately for messaging platforms (short paragraphs, no complex markdown).";

    // Registered agents, set by AgentProcessor via reflection at startup
    private static final CopyOnWriteArrayList<AgentBinding> agentBindings = new CopyOnWriteArrayList<>();
    private static final int MAX_CONCURRENT_MESSAGES = 64;

    private final Semaphore messageSemaphore = new Semaphore(MAX_CONCURRENT_MESSAGES);
    private final Map<String, MessagingChannel> channelsByType;
    private final ChannelFilterChain filterChain;

    /**
     * Binding for a single {@code @Agent} registered with the bridge.
     * Commands route through all bindings in registration order (first match wins).
     */
    record AgentBinding(String name, Object router, Method routeMethod,
                        String systemPrompt, AiPipeline aiPipeline,
                        List<String> allowedChannels) {}

    public ChannelAiBridge(List<MessagingChannel> channels, ChannelFilterChain filterChain) {
        this.channelsByType = new ConcurrentHashMap<>();
        this.filterChain = filterChain;
        for (MessagingChannel channel : channels) {
            channelsByType.put(channel.channelType().id(), channel);
        }
    }

    /**
     * Clears all registered agent bindings. Intended for test isolation and
     * dev-mode reload scenarios where static state must not leak between
     * test runs or application restarts.
     */
    static void reset() {
        agentBindings.clear();
    }

    /**
     * Register an {@code @Agent}'s CommandRouter, system prompt, and AI pipeline
     * with the bridge. Multiple agents can be registered; commands are routed in
     * registration order (first match wins). Called via reflection by the agent module.
     *
     * @param name         the agent name (from {@code @Agent(name=...)})
     * @param router       the CommandRouter instance
     * @param target       the agent instance (unused here, reserved for future use)
     * @param systemPrompt the agent's system prompt (may be null)
     * @param aiPipeline   the agent's AI pipeline for NL message handling (may be null)
     */
    public static void registerAgent(String name, Object router, Object target,
                                     String systemPrompt, Object aiPipeline) {
        registerAgent(name, router, target, systemPrompt, aiPipeline, List.of());
    }

    /**
     * Register an {@code @Agent}'s CommandRouter, system prompt, AI pipeline, and
     * allowed channels with the bridge. When {@code allowedChannels} is non-empty,
     * the agent only handles messages from the listed channel types.
     *
     * @param name            the agent name (from {@code @Agent(name=...)})
     * @param router          the CommandRouter instance
     * @param target          the agent instance (unused here, reserved for future use)
     * @param systemPrompt    the agent's system prompt (may be null)
     * @param aiPipeline      the agent's AI pipeline for NL message handling (may be null)
     * @param allowedChannels channel type IDs this agent handles (empty = all channels)
     */
    public static void registerAgent(String name, Object router, Object target,
                                     String systemPrompt, Object aiPipeline,
                                     List<String> allowedChannels) {
        try {
            var method = router.getClass().getMethod("route", String.class, String.class);
            var pipeline = aiPipeline instanceof AiPipeline p ? p : null;
            var normalized = allowedChannels != null
                    ? allowedChannels.stream().map(String::toLowerCase).toList()
                    : List.<String>of();
            agentBindings.add(new AgentBinding(name, router, method, systemPrompt,
                    pipeline, normalized));
            logger.info("ChannelAiBridge: agent '{}' registered (pipeline={}, channels={}) "
                            + "— {} agent(s) active on channels",
                    name, pipeline != null,
                    normalized.isEmpty() ? "all" : normalized,
                    agentBindings.size());
        } catch (NoSuchMethodException e) {
            logger.error("CommandRouter for agent '{}' does not have route(String, String) method", name, e);
        }
    }

    /**
     * Handle an incoming message: dispatches AI call on a virtual thread so the
     * webhook servlet thread returns immediately, preventing thread-pool exhaustion
     * under load from busy Slack/Telegram bots.
     */
    public void handleMessage(IncomingMessage incoming) {
        if (!messageSemaphore.tryAcquire()) {
            logger.warn("Message backpressure: dropping message from {} (>{} concurrent)",
                    incoming.channelType().id(), MAX_CONCURRENT_MESSAGES);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                handleMessageAsync(incoming);
            } finally {
                messageSemaphore.release();
            }
        });
    }

    private void handleMessageAsync(IncomingMessage incoming) {
        logger.info("[{}] {} says: {}",
                incoming.channelType().id(),
                incoming.senderName().orElse(incoming.senderId()),
                incoming.text().substring(0, Math.min(80, incoming.text().length())));

        MessagingChannel channel = channelsByType.get(incoming.channelType().id());
        if (channel == null) {
            logger.warn("No channel adapter for {}", incoming.channelType());
            return;
        }

        // Route through CommandRouter first if an @Agent is registered
        String response = routeCommandOrAi(incoming);

        // Truncate if exceeding channel limit
        if (response.length() > channel.maxMessageLength()) {
            response = response.substring(0, channel.maxMessageLength() - 3) + "...";
        }

        try {
            var outgoing = new OutgoingMessage(
                    incoming.conversationId(),
                    response,
                    Optional.of(incoming.messageId()),
                    Optional.empty()
            );

            // Apply outbound filters (message splitting, PII redaction, etc.)
            var filtered = filterChain.filterOutgoing(outgoing, incoming.channelType());
            if (filtered == null) {
                logger.debug("[{}] Outbound message blocked by filter", incoming.channelType().id());
                return;
            }

            var receipt = channel.send(filtered);
            logger.info("[{}] Response sent ({})",
                    incoming.channelType().id(),
                    receipt.channelMessageId().orElse("ok"));
        } catch (Exception e) {
            logger.error("[{}] Failed to send response: {}",
                    incoming.channelType().id(), e.getMessage());
        }
    }

    /**
     * Routes the message through all registered agents' CommandRouters in
     * registration order. The first router that returns {@code Executed} or
     * {@code ConfirmationRequired} wins. If all return {@code NotACommand}
     * (or no agents are registered), falls through to the LLM.
     */
    private String routeCommandOrAi(IncomingMessage incoming) {
        var clientId = incoming.channelType().id() + ":" + incoming.senderId();
        var channelId = incoming.channelType().id().toLowerCase();
        for (var binding : agentBindings) {
            if (!binding.allowedChannels().isEmpty()
                    && !binding.allowedChannels().contains(channelId)) {
                logger.debug("Skipping agent '{}' — channel '{}' not in allowed list {}",
                        binding.name(), channelId, binding.allowedChannels());
                continue;
            }
            try {
                var result = binding.routeMethod().invoke(binding.router(), clientId, incoming.text());
                var simpleName = result.getClass().getSimpleName();

                if ("Executed".equals(simpleName)) {
                    var responseMethod = result.getClass().getMethod("response");
                    return (String) responseMethod.invoke(result);
                }
                if ("ConfirmationRequired".equals(simpleName)) {
                    var promptMethod = result.getClass().getMethod("prompt");
                    return (String) promptMethod.invoke(result);
                }
                // NotACommand — try next agent
            } catch (Exception e) {
                logger.warn("CommandRouter for agent '{}' failed, trying next: {}",
                        binding.name(), e.getMessage());
            }
        }
        return callAi(incoming);
    }

    /**
     * Routes natural-language messages through the AI pipeline.
     *
     * <p><strong>Limitation:</strong> when multiple agents are registered,
     * NL messages are handled by the first agent that has a pipeline.
     * There is no content-based routing across agents — only command
     * routing supports multi-agent dispatch. This is a known design
     * limitation; a future routing strategy (e.g., keyword-based or
     * LLM-based agent selection) could address it.</p>
     */
    private String callAi(IncomingMessage incoming) {
        var clientId = incoming.channelType().id() + ":" + incoming.senderId();
        var channelId = incoming.channelType().id().toLowerCase();
        var text = incoming.text();

        // Fast-path: route @RequiresApproval protocol responses ("/__approval/<id>/approve")
        // through the pipeline's ApprovalRegistry before treating them as new prompts.
        // Without this, an approval message sent over a channel (Slack/Telegram/etc.)
        // would be forwarded to the LLM as a literal user message, and the parked
        // virtual thread waiting on the approval future would time out unused.
        if (org.atmosphere.ai.approval.ApprovalRegistry.isApprovalMessage(text)) {
            for (var binding : agentBindings) {
                if (!binding.allowedChannels().isEmpty()
                        && !binding.allowedChannels().contains(channelId)) {
                    continue;
                }
                if (binding.aiPipeline() != null) {
                    var result = binding.aiPipeline().approvalRegistry().resolve(text);
                    if (result == org.atmosphere.ai.approval.ApprovalRegistry.ResolveResult.RESOLVED) {
                        logger.debug("Approval response routed through agent '{}' on channel '{}'",
                                binding.name(), channelId);
                        return "";
                    }
                    // UNKNOWN_ID means this registry didn't own the approval —
                    // continue to the next binding. NOT_APPROVAL_MESSAGE is
                    // unreachable here because isApprovalMessage pre-filtered.
                }
            }
            logger.debug("Approval-shaped message had no matching pending approval on channel '{}'",
                    channelId);
            return "";
        }

        // First registered agent with a pipeline handles NL messages
        for (var binding : agentBindings) {
            if (!binding.allowedChannels().isEmpty()
                    && !binding.allowedChannels().contains(channelId)) {
                continue;
            }
            if (binding.aiPipeline() != null) {
                var collector = new CollectingSession();
                try {
                    binding.aiPipeline().execute(clientId, text, collector);
                    return collector.getResponse();
                } catch (Exception e) {
                    logger.error("AI pipeline for agent '{}' failed: {}",
                            binding.name(), e.getMessage());
                }
            }
        }

        // Fallback: raw LLM call when no agent pipeline is available
        return callAiRaw(text);
    }

    /**
     * Raw LLM fallback for when no agent pipeline is registered (e.g., channels
     * deployed without atmosphere-agent).
     */
    private String callAiRaw(String userMessage) {
        var settings = AiConfig.get();
        if (settings == null || settings.apiKey() == null || settings.apiKey().isBlank()) {
            return "Hello! I received your message: \"" + userMessage
                    + "\"\n\nI'm in demo mode. Configure atmosphere.ai.api-key to enable real AI responses.";
        }

        var first = agentBindings.isEmpty() ? null : agentBindings.get(0);
        var prompt = (first != null && first.systemPrompt() != null && !first.systemPrompt().isBlank())
                ? first.systemPrompt() : DEFAULT_SYSTEM_PROMPT;
        var collector = new CollectingSession();
        var request = ChatCompletionRequest.builder(settings.model())
                .system(prompt)
                .user(userMessage)
                .build();

        settings.client().streamChatCompletion(request, collector);
        return collector.getResponse();
    }

    /**
     * Collects streaming tokens into a string, blocking until complete.
     */
    private static class CollectingSession implements StreamingSession {

        private final StringBuilder buffer = new StringBuilder();
        private final java.util.concurrent.locks.ReentrantLock bufferLock = new java.util.concurrent.locks.ReentrantLock();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final String id = UUID.randomUUID().toString();
        private volatile boolean closed;

        @Override public String sessionId() { return id; }

        @Override public void send(String text) {
            bufferLock.lock();
            try { buffer.append(text); } finally { bufferLock.unlock(); }
        }

        @Override public void sendMetadata(String key, Object value) {}
        @Override public void progress(String message) {}

        @Override public void complete() { closed = true; latch.countDown(); }

        @Override
        public void complete(String summary) {
            if (summary != null && !summary.isBlank()) {
                bufferLock.lock();
                try { buffer.setLength(0); buffer.append(summary); } finally { bufferLock.unlock(); }
            }
            closed = true;
            latch.countDown();
        }

        @Override
        public void error(Throwable t) {
            bufferLock.lock();
            try {
                if (buffer.isEmpty()) {
                    buffer.append("Error: ").append(
                            t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                }
            } finally {
                bufferLock.unlock();
            }
            closed = true;
            latch.countDown();
        }
        @Override public boolean isClosed() { return closed; }

        String getResponse() {
            try { latch.await(120, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            bufferLock.lock();
            try { return buffer.toString(); } finally { bufferLock.unlock(); }
        }
    }
}
