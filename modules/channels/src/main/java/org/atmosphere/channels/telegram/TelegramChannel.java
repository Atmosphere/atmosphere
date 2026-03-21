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
package org.atmosphere.channels.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.atmosphere.channels.ChannelException;
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
 * Telegram Bot API channel adapter.
 * <p>
 * Combines webhook verification (secret token header), inbound Update parsing,
 * and outbound sendMessage/editMessageText via the Bot API.
 * <p>
 * Ported from <a href="https://github.com/dravr-ai/dravr-canot">Canot</a>'s
 * Rust Telegram adapter.
 */
public class TelegramChannel implements MessagingChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final String BOT_API = "https://api.telegram.org/bot";

    private final String botToken;
    private final String webhookSecret;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TelegramChannel(String botToken, String webhookSecret, ObjectMapper objectMapper) {
        this.botToken = botToken;
        this.webhookSecret = webhookSecret;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public String webhookPath() {
        return "/webhook/telegram";
    }

    @Override
    public int maxMessageLength() {
        return 4096;
    }

    @Override
    public void verifySignature(Map<String, String> headers, byte[] body) {
        String token = headers.get("x-telegram-bot-api-secret-token");
        if (token == null) {
            throw new ChannelException(ChannelType.TELEGRAM,
                    "Missing X-Telegram-Bot-Api-Secret-Token header");
        }
        // Constant-time comparison
        if (!MessageDigest.isEqual(token.getBytes(), webhookSecret.getBytes())) {
            throw new ChannelException(ChannelType.TELEGRAM, "Secret token mismatch");
        }
    }

    @Override
    public List<IncomingMessage> receive(Map<String, String> headers, byte[] body) {
        try {
            JsonNode update = objectMapper.readTree(body);

            JsonNode message = update.get("message");
            if (message == null || !message.has("text")) {
                return List.of();
            }

            long chatId = message.path("chat").path("id").longValue();
            long fromId = message.path("from").path("id").longValue(chatId);
            String fromName = message.path("from").path("first_name").stringValue(null);
            String text = message.path("text").stringValue("");
            long messageId = message.path("message_id").longValue();

            return List.of(new IncomingMessage(
                    ChannelType.TELEGRAM,
                    String.valueOf(fromId),
                    Optional.ofNullable(fromName),
                    text,
                    String.valueOf(chatId),
                    String.valueOf(messageId),
                    Instant.now()
            ));
        } catch (Exception e) {
            throw new ChannelException(ChannelType.TELEGRAM,
                    "Failed to parse Telegram update: " + e.getMessage(), e, false);
        }
    }

    @Override
    public DeliveryReceipt send(OutgoingMessage message) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("chat_id", message.recipientId());
            payload.put("text", message.text());
            payload.put("parse_mode", message.parseMode().orElse("HTML"));

            if (message.replyTo().isPresent()) {
                payload.put("reply_to_message_id", message.replyTo().get());
            }

            String responseBody = callBotApi("sendMessage", payload);
            JsonNode result = objectMapper.readTree(responseBody);

            String channelMessageId = result.path("result").path("message_id").stringValue(null);

            return new DeliveryReceipt(
                    Optional.ofNullable(channelMessageId),
                    DeliveryReceipt.DeliveryStatus.SENT,
                    Instant.now()
            );
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.TELEGRAM,
                    "Failed to send message: " + e.getMessage(), e, true);
        }
    }

    /**
     * Edit an existing message (for progressive streaming on Telegram).
     *
     * @param chatId    the chat to edit in
     * @param messageId the message to edit
     * @param newText   the updated text
     */
    public void editMessage(String chatId, String messageId, String newText) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("chat_id", chatId);
            payload.put("message_id", messageId);
            payload.put("text", newText);
            payload.put("parse_mode", "HTML");

            callBotApi("editMessageText", payload);
        } catch (Exception e) {
            log.warn("Failed to edit Telegram message: {}", e.getMessage());
        }
    }

    private String callBotApi(String method, ObjectNode payload) {
        try {
            String url = BOT_API + botToken + "/" + method;
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ChannelException(ChannelType.TELEGRAM,
                        "Bot API returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode() >= 500);
            }

            return response.body();
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.TELEGRAM,
                    "Bot API call failed: " + e.getMessage(), e, true);
        }
    }
}
