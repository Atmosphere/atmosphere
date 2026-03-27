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
package org.atmosphere.samples.channels;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.channels.ChannelWebhookController;
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.MessagingChannel;
import org.atmosphere.channels.OutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Bridges external messaging channels (Telegram, Slack, etc.) to the AI backend.
 * <p>
 * When a message arrives via webhook:
 * <ol>
 *   <li>Sends it to the configured LLM (same as the web chat)</li>
 *   <li>Collects the streaming response into a complete string</li>
 *   <li>Sends the response back through the platform's API</li>
 * </ol>
 */
@Component
public class ChannelBridge {

    private static final Logger logger = LoggerFactory.getLogger(ChannelBridge.class);
    private static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant. Keep responses concise and friendly.";

    private final ChannelWebhookController webhookController;
    private final Map<String, MessagingChannel> channelsByType;

    public ChannelBridge(ChannelWebhookController webhookController,
                         List<MessagingChannel> channels) {
        this.webhookController = webhookController;
        this.channelsByType = new ConcurrentHashMap<>();
        for (MessagingChannel channel : channels) {
            channelsByType.put(channel.channelType().id(), channel);
        }
    }

    @PostConstruct
    void init() {
        webhookController.addMessageHandler(this::handleIncomingMessage);
        logger.info("ChannelBridge initialized — routing {} channel(s) to AI",
                channelsByType.size());
    }

    private void handleIncomingMessage(IncomingMessage incoming) {
        logger.info("[{}] Message from {}: {}",
                incoming.channelType().id(),
                incoming.senderName().orElse(incoming.senderId()),
                incoming.text());

        MessagingChannel channel = channelsByType.get(incoming.channelType().id());
        if (channel == null) {
            logger.warn("No channel adapter for {}", incoming.channelType());
            return;
        }

        String response = generateResponse(incoming.text());
        sendResponse(channel, incoming, response);
    }

    private String generateResponse(String userMessage) {
        var settings = AiConfig.get();
        if (settings == null || settings.apiKey() == null || settings.apiKey().isBlank()) {
            return "Hello from Atmosphere! I received your message: \""
                    + userMessage + "\"\n\nI'm in demo mode. Set LLM_API_KEY to enable real AI.";
        }

        // Use the configured LLM — collect streaming tokens into a string
        var collector = new CollectingSession();
        var request = ChatCompletionRequest.builder(settings.model())
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .build();

        settings.client().streamChatCompletion(request, collector);
        return collector.getResponse();
    }

    private void sendResponse(MessagingChannel channel, IncomingMessage incoming, String response) {
        try {
            // Truncate if the response exceeds the channel's limit
            String text = response.length() > channel.maxMessageLength()
                    ? response.substring(0, channel.maxMessageLength() - 3) + "..."
                    : response;

            var outgoing = new OutgoingMessage(
                    incoming.conversationId(),
                    text,
                    Optional.of(incoming.messageId()),
                    Optional.empty()
            );
            var receipt = channel.send(outgoing);
            logger.info("[{}] Response sent (messageId: {})",
                    incoming.channelType().id(),
                    receipt.channelMessageId().orElse("unknown"));
        } catch (Exception e) {
            logger.error("[{}] Failed to send response: {}",
                    incoming.channelType().id(), e.getMessage());
        }
    }
}
