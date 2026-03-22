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
package org.atmosphere.channels.discord;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.atmosphere.channels.ChannelException;
import org.atmosphere.channels.ChannelHttpClient;
import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.DeliveryReceipt;
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.MessagingChannel;
import org.atmosphere.channels.OutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Discord channel adapter using the Gateway WebSocket API for receiving
 * messages and the REST API for sending.
 * <p>
 * Unlike the Interactions API (which only handles slash commands), the Gateway
 * receives real MESSAGE_CREATE events from users in channels and DMs.
 * Requires the MESSAGE_CONTENT privileged intent.
 * <p>
 * Ported from <a href="https://github.com/dravr-ai/dravr-canot">Canot</a>'s
 * Rust Discord Gateway implementation.
 */
public class DiscordChannel implements MessagingChannel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);
    private static final String DISCORD_API = "https://discord.com/api/v10";

    private final String botToken;
    private final ObjectMapper objectMapper;
    private final DiscordGateway gateway;

    public DiscordChannel(String botToken, ObjectMapper objectMapper,
                          java.util.function.Consumer<IncomingMessage> messageHandler) {
        this.botToken = botToken;
        this.objectMapper = objectMapper;
        this.gateway = new DiscordGateway(botToken, objectMapper, messageHandler);
    }

    /**
     * Start the Gateway WebSocket connection.
     * Called by auto-configuration after the bean is created.
     */
    public void start() {
        gateway.start();
    }

    /**
     * Stop the Gateway connection.
     */
    public void stop() {
        gateway.stop();
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.DISCORD;
    }

    @Override
    public String webhookPath() {
        return "/webhook/discord"; // Not used — Gateway receives messages directly
    }

    @Override
    public int maxMessageLength() {
        return 2000;
    }

    @Override
    public void verifySignature(Map<String, String> headers, byte[] body) {
        // Gateway handles authentication via the bot token — no webhook verification needed
    }

    @Override
    public List<IncomingMessage> receive(Map<String, String> headers, byte[] body) {
        // Messages arrive via the Gateway, not webhooks
        return List.of();
    }

    @Override
    public DeliveryReceipt send(OutgoingMessage message) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("content", message.text());

            String url = DISCORD_API + "/channels/" + message.recipientId() + "/messages";
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bot " + botToken)
                    .timeout(ChannelHttpClient.requestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = ChannelHttpClient.get().send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ChannelException(ChannelType.DISCORD,
                        "Discord API returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode() >= 500);
            }

            JsonNode result = objectMapper.readTree(response.body());
            String messageId = result.path("id").stringValue(null);

            return new DeliveryReceipt(
                    Optional.ofNullable(messageId),
                    DeliveryReceipt.DeliveryStatus.SENT,
                    Instant.now()
            );
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.DISCORD,
                    "Failed to send message: " + e.getMessage(), e, true);
        }
    }

    /**
     * Edit an existing message (for progressive streaming on Discord).
     */
    public void editMessage(String channelId, String messageId, String newText) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("content", newText);

            String url = DISCORD_API + "/channels/" + channelId + "/messages/" + messageId;
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bot " + botToken)
                    .timeout(ChannelHttpClient.requestTimeout())
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                    .build();

            ChannelHttpClient.get().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Failed to edit Discord message: {}", e.getMessage());
        }
    }
}
