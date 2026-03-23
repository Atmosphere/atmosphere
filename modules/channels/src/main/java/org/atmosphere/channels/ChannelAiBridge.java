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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.atmosphere.ai.AiConfig;
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

    // Set by AgentProcessor via reflection when atmosphere-agent is on the classpath
    private static volatile Object commandRouterInstance;
    private static volatile Object commandRouterTarget;
    private static volatile Method routeMethod;
    private static volatile String agentSystemPrompt;

    private final Map<String, MessagingChannel> channelsByType;
    private final ChannelFilterChain filterChain;

    public ChannelAiBridge(List<MessagingChannel> channels, ChannelFilterChain filterChain) {
        this.channelsByType = new ConcurrentHashMap<>();
        this.filterChain = filterChain;
        for (MessagingChannel channel : channels) {
            channelsByType.put(channel.channelType().id(), channel);
        }
    }

    /**
     * Set a {@code CommandRouter} to handle slash commands before falling through
     * to the LLM. Called via reflection by the agent module when both
     * {@code atmosphere-agent} and {@code atmosphere-channels} are on the classpath.
     *
     * @param router the CommandRouter instance
     * @param target the agent instance (unused here, reserved for future use)
     */
    public static void setCommandRouter(Object router, Object target) {
        commandRouterInstance = router;
        commandRouterTarget = target;
        try {
            routeMethod = router.getClass().getMethod("route", String.class, String.class);
        } catch (NoSuchMethodException e) {
            logger.error("CommandRouter does not have route(String, String) method", e);
            commandRouterInstance = null;
        }
        logger.info("ChannelAiBridge: CommandRouter wired from @Agent — slash commands active on all channels");
    }

    /**
     * Set the system prompt from the agent's skill file. If not set, falls back
     * to the default generic prompt.
     *
     * @param prompt the system prompt text
     */
    public static void setSystemPrompt(String prompt) {
        if (prompt != null && !prompt.isBlank()) {
            agentSystemPrompt = prompt;
            logger.info("ChannelAiBridge: using agent skill file system prompt ({} chars)", prompt.length());
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
     * Routes the message through the CommandRouter if available. If the message
     * is not a command (or no CommandRouter is set), falls through to the LLM.
     */
    private String routeCommandOrAi(IncomingMessage incoming) {
        if (commandRouterInstance != null && routeMethod != null) {
            try {
                var clientId = incoming.channelType().id() + ":" + incoming.senderId();
                var result = routeMethod.invoke(commandRouterInstance, clientId, incoming.text());

                // Use reflection to check the sealed interface variants
                var resultClass = result.getClass();
                var simpleName = resultClass.getSimpleName();

                if ("Executed".equals(simpleName)) {
                    var responseMethod = resultClass.getMethod("response");
                    return (String) responseMethod.invoke(result);
                }
                if ("ConfirmationRequired".equals(simpleName)) {
                    var promptMethod = resultClass.getMethod("prompt");
                    return (String) promptMethod.invoke(result);
                }
                // NotACommand — fall through to AI
            } catch (Exception e) {
                logger.warn("CommandRouter invocation failed, falling through to AI: {}", e.getMessage());
            }
        }
        return callAi(incoming.text());
    }

    private String callAi(String userMessage) {
        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            return "Hello! I received your message: \"" + userMessage
                    + "\"\n\nI'm in demo mode. Configure atmosphere.ai.api-key to enable real AI responses.";
        }

        var prompt = agentSystemPrompt != null ? agentSystemPrompt : DEFAULT_SYSTEM_PROMPT;
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
