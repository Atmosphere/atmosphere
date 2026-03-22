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
package org.atmosphere.samples.springboot.dentist;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.agent.command.CommandRegistry;
import org.atmosphere.agent.command.CommandResult;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.channels.ChannelWebhookController;
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.MessagingChannel;
import org.atmosphere.channels.OutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges Slack/Telegram messages to the dentist agent — routes commands first,
 * then falls through to LLM. Overrides the auto-configured {@code ChannelAiBridge}
 * by registering on {@code ContextRefreshedEvent} (runs after all auto-config).
 */
@Component
public class ChannelBridge {

    private static final Logger logger = LoggerFactory.getLogger(ChannelBridge.class);

    private final ChannelWebhookController webhookController;
    private final Map<String, MessagingChannel> channelsByType;
    private final CommandRouter commandRouter;

    public ChannelBridge(Optional<ChannelWebhookController> webhookController,
                         Optional<List<MessagingChannel>> channels) {
        this.webhookController = webhookController.orElse(null);
        this.channelsByType = new ConcurrentHashMap<>();
        channels.ifPresent(list -> {
            for (MessagingChannel channel : list) {
                channelsByType.put(channel.channelType().id(), channel);
            }
        });

        var registry = new CommandRegistry();
        registry.scan(DentistAgent.class);
        this.commandRouter = new CommandRouter(registry, new DentistAgent());
    }

    @EventListener(ContextRefreshedEvent.class)
    void init() {
        if (webhookController == null) {
            return;
        }
        // Last caller wins — overrides auto-configured ChannelAiBridge
        webhookController.onMessage(this::handleIncomingMessage);
        logger.info("Dentist ChannelBridge initialized — routing {} channel(s)",
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

        // Route commands first
        var clientId = incoming.channelType().id() + ":" + incoming.senderId();
        var result = commandRouter.route(clientId, incoming.text());

        String response = switch (result) {
            case CommandResult.Executed exec -> exec.response();
            case CommandResult.ConfirmationRequired confirm -> confirm.prompt();
            case CommandResult.NotACommand ignored -> generateAiResponse(incoming.text());
        };

        sendResponse(channel, incoming, response);
    }

    private String generateAiResponse(String userMessage) {
        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null
                || settings.client().apiKey().isBlank()) {
            return DemoResponseProducer.generateDemoResponse(userMessage);
        }

        var collector = new CollectingSession();
        var systemPrompt = "You are Dr. Molar, a friendly dental emergency assistant. "
                + "Help patients with broken teeth. Be empathetic, recommend first aid, "
                + "and always remind them to see a real dentist. Keep responses under 500 words.";
        var request = ChatCompletionRequest.builder(settings.model())
                .system(systemPrompt)
                .user(userMessage)
                .build();

        settings.client().streamChatCompletion(request, collector);
        return collector.getResponse();
    }

    private void sendResponse(MessagingChannel channel, IncomingMessage incoming, String response) {
        try {
            String text = response.length() > channel.maxMessageLength()
                    ? response.substring(0, channel.maxMessageLength() - 3) + "..."
                    : response;

            var outgoing = new OutgoingMessage(
                    incoming.conversationId(),
                    text,
                    Optional.of(incoming.messageId()),
                    Optional.of("Markdown")
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
