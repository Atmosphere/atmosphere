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

    private final Map<String, MessagingChannel> channelsByType;
    private final ChannelFilterChain filterChain;

    /**
     * Binding for a single {@code @Agent} registered with the bridge.
     * Commands route through all bindings in registration order (first match wins).
     */
    record AgentBinding(String name, Object router, Method routeMethod,
                        String systemPrompt, AiPipeline aiPipeline) {}

    public ChannelAiBridge(List<MessagingChannel> channels, ChannelFilterChain filterChain) {
        this.channelsByType = new ConcurrentHashMap<>();
        this.filterChain = filterChain;
        for (MessagingChannel channel : channels) {
            channelsByType.put(channel.channelType().id(), channel);
        }
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
        try {
            var method = router.getClass().getMethod("route", String.class, String.class);
            var pipeline = aiPipeline instanceof AiPipeline p ? p : null;
            agentBindings.add(new AgentBinding(name, router, method, systemPrompt, pipeline));
            logger.info("ChannelAiBridge: agent '{}' registered (pipeline={}) — {} agent(s) active on channels",
                    name, pipeline != null, agentBindings.size());
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
        Thread.startVirtualThread(() -> handleMessageAsync(incoming));
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
        for (var binding : agentBindings) {
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
     * Routes natural-language messages through the full AI pipeline (memory,
     * tools, guardrails, RAG, metrics) if an agent pipeline is registered.
     * Falls back to a raw LLM call if no agent is registered, or to demo mode
     * if no API key is configured.
     */
    private String callAi(IncomingMessage incoming) {
        var clientId = incoming.channelType().id() + ":" + incoming.senderId();

        // Use the first registered agent's pipeline if available
        for (var binding : agentBindings) {
            if (binding.aiPipeline() != null) {
                var collector = new CollectingSession();
                try {
                    binding.aiPipeline().execute(clientId, incoming.text(), collector);
                    return collector.getResponse();
                } catch (Exception e) {
                    logger.error("AI pipeline for agent '{}' failed: {}",
                            binding.name(), e.getMessage());
                }
            }
        }

        // Fallback: raw LLM call when no agent pipeline is available
        return callAiRaw(incoming.text());
    }

    /**
     * Raw LLM fallback for when no agent pipeline is registered (e.g., channels
     * deployed without atmosphere-agent).
     */
    private String callAiRaw(String userMessage) {
        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
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
        private final CountDownLatch latch = new CountDownLatch(1);
        private final String id = UUID.randomUUID().toString();
        private volatile boolean closed;

        @Override public String sessionId() { return id; }

        @Override public void send(String text) {
            synchronized (buffer) { buffer.append(text); }
        }

        @Override public void sendMetadata(String key, Object value) {}
        @Override public void progress(String message) {}

        @Override public void complete() { closed = true; latch.countDown(); }
        @Override public void complete(String summary) { closed = true; latch.countDown(); }
        @Override public void error(Throwable t) { closed = true; latch.countDown(); }
        @Override public boolean isClosed() { return closed; }

        String getResponse() {
            try { latch.await(120, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            synchronized (buffer) { return buffer.toString(); }
        }
    }
}
