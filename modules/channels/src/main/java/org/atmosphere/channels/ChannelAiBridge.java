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
        AMBIGUOUS_ROUTING_WARNED.set(false);
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
            // Prefer the IncomingMessage-aware overload when present so
            // @Command methods declared with an IncomingMessage parameter
            // receive the originating platform context. Falls back to the
            // String-only overload for older routers.
            Method method;
            try {
                method = router.getClass().getMethod("route",
                        String.class, String.class, IncomingMessage.class);
            } catch (NoSuchMethodException e) {
                method = router.getClass().getMethod("route", String.class, String.class);
            }
            var pipeline = aiPipeline instanceof AiPipeline p ? p : null;
            var normalized = allowedChannels != null
                    ? allowedChannels.stream().map(String::toLowerCase).toList()
                    : List.<String>of();
            agentBindings.add(new AgentBinding(name, router, method, systemPrompt,
                    pipeline, normalized));
            logger.info("ChannelAiBridge: agent '{}' registered (pipeline={}, channels={}, "
                            + "incomingMessageAware={}) — {} agent(s) active on channels",
                    name, pipeline != null,
                    normalized.isEmpty() ? "all" : normalized,
                    method.getParameterCount() == 3,
                    agentBindings.size());
        } catch (NoSuchMethodException e) {
            logger.error("CommandRouter for agent '{}' does not have a route(String, String[, IncomingMessage]) method", name, e);
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
                var result = binding.routeMethod().getParameterCount() == 3
                        ? binding.routeMethod().invoke(binding.router(), clientId, incoming.text(), incoming)
                        : binding.routeMethod().invoke(binding.router(), clientId, incoming.text());
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
     * <p>Multi-agent routing: an explicit {@code @name}/{@code name:}
     * address at the start of the message wins, then the configured
     * default agent ({@link #DEFAULT_AGENT_PROPERTY}), then the sole
     * eligible agent on the channel. Ambiguous unaddressed traffic falls
     * back to first-registered with a one-time WARN.</p>
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

        // Multi-agent routing for free text: explicit "@agent ..." /
        // "agent: ..." addressing wins, then the configured default agent
        // (atmosphere.channels.default-agent), then the sole eligible
        // agent. Only when several agents share the channel unaddressed
        // and no default is configured does first-registered apply — with
        // a one-time WARN, because registration order is not a routing
        // policy anyone chose.
        var eligible = new java.util.ArrayList<AgentBinding>();
        for (var binding : agentBindings) {
            if (!binding.allowedChannels().isEmpty()
                    && !binding.allowedChannels().contains(channelId)) {
                continue;
            }
            if (binding.aiPipeline() != null) {
                eligible.add(binding);
            }
        }
        if (eligible.isEmpty()) {
            // Fallback: raw LLM call when no agent pipeline is available
            return callAiRaw(text);
        }

        var route = routeFreeText(text, eligible, configuredDefaultAgent());
        if (route.ambiguous() && AMBIGUOUS_ROUTING_WARNED.compareAndSet(false, true)) {
            logger.warn("{} agents share channel '{}' and the message names none of "
                    + "them — routing to first-registered '{}'. Address agents with "
                    + "'@name ...' or set -D{} to choose the default.",
                    eligible.size(), channelId, route.binding().name(), DEFAULT_AGENT_PROPERTY);
        }

        var collector = new CollectingSession();
        try {
            route.binding().aiPipeline().execute(clientId, route.text(), collector);
            return collector.getResponse();
        } catch (Exception e) {
            logger.error("AI pipeline for agent '{}' failed: {}",
                    route.binding().name(), e.getMessage());
        }

        // Fallback: raw LLM call when the chosen agent pipeline failed
        return callAiRaw(text);
    }

    /**
     * The routing decision for one free-text message: the chosen binding,
     * the text to dispatch (address prefix stripped), and whether the
     * choice fell back to registration order with no signal from the
     * message or configuration.
     */
    record Route(AgentBinding binding, String text, boolean ambiguous) { }

    /**
     * Choose the agent for an unrouted free-text message: explicit
     * {@code @name} / {@code name:} addressing wins, then the configured
     * default agent, then the sole eligible agent; several unaddressed
     * candidates without a default fall back to first-registered and are
     * flagged {@code ambiguous}.
     */
    static Route routeFreeText(String text, List<AgentBinding> eligible,
                               String defaultAgentName) {
        var addressed = addressedAgent(text, eligible);
        if (addressed != null) {
            return new Route(addressed.binding(), addressed.remainder(), false);
        }
        if (eligible.size() == 1) {
            return new Route(eligible.get(0), text, false);
        }
        for (var binding : eligible) {
            if (binding.name() != null && binding.name().equalsIgnoreCase(defaultAgentName)) {
                return new Route(binding, text, false);
            }
        }
        return new Route(eligible.get(0), text, true);
    }

    /** System property naming the agent that receives unaddressed free text on shared channels. */
    public static final String DEFAULT_AGENT_PROPERTY = "atmosphere.channels.default-agent";
    /** Environment-variable equivalent of {@link #DEFAULT_AGENT_PROPERTY}. */
    public static final String DEFAULT_AGENT_ENV = "ATMOSPHERE_CHANNELS_DEFAULT_AGENT";

    private static final java.util.concurrent.atomic.AtomicBoolean AMBIGUOUS_ROUTING_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static String configuredDefaultAgent() {
        var prop = System.getProperty(DEFAULT_AGENT_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        var env = System.getenv(DEFAULT_AGENT_ENV);
        return env != null ? env.trim() : "";
    }

    /** An explicitly addressed agent plus the message with the address stripped. */
    private record Addressed(AgentBinding binding, String remainder) { }

    /**
     * Match "@name ..." or "name: ..." (case-insensitive) at the start of a
     * free-text message against the eligible bindings. The name must end at
     * a word boundary so agent "research" never claims a message for
     * "@researcher".
     */
    private static Addressed addressedAgent(String text, List<AgentBinding> eligible) {
        if (text == null) {
            return null;
        }
        var trimmed = text.stripLeading();
        for (var binding : eligible) {
            var name = binding.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            for (var prefix : new String[] {"@" + name, name + ":"}) {
                if (!trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    continue;
                }
                if (prefix.charAt(0) == '@' && trimmed.length() > prefix.length()) {
                    var boundary = trimmed.charAt(prefix.length());
                    if (!Character.isWhitespace(boundary) && boundary != ':') {
                        continue;
                    }
                }
                var rest = trimmed.substring(prefix.length()).stripLeading();
                if (rest.startsWith(":")) {
                    rest = rest.substring(1).stripLeading();
                }
                if (!rest.isEmpty()) {
                    return new Addressed(binding, rest);
                }
            }
        }
        return null;
    }

    /**
     * Raw LLM fallback for when no agent pipeline is registered (e.g., channels
     * deployed without atmosphere-agent).
     */
    private String callAiRaw(String userMessage) {
        var settings = AiConfig.get();
        if (settings == null || !settings.hasReachableModel()) {
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
