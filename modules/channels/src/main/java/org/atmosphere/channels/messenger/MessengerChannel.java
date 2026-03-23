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
package org.atmosphere.channels.messenger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.atmosphere.channels.ChannelHttpClient;
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
 * Meta Messenger Platform channel adapter.
 * <p>
 * Verification: HMAC-SHA256 via {@code x-hub-signature-256} header (same as WhatsApp).
 * Rendering: Messenger Send API with text messages.
 */
public class MessengerChannel implements MessagingChannel {

    private static final Logger log = LoggerFactory.getLogger(MessengerChannel.class);
    private static final String GRAPH_API = "https://graph.facebook.com/v21.0/me/messages";

    private final String pageAccessToken;
    private final String appSecret;
    private final ObjectMapper objectMapper;

    public MessengerChannel(String pageAccessToken, String appSecret, ObjectMapper objectMapper) {
        this.pageAccessToken = pageAccessToken;
        this.appSecret = appSecret;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.MESSENGER;
    }

    @Override
    public String webhookPath() {
        return "/webhook/messenger";
    }

    @Override
    public int maxMessageLength() {
        return 2000;
    }

    @Override
    public void verifySignature(Map<String, String> headers, byte[] body) {
        String signature = headers.get("x-hub-signature-256");
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new ChannelException(ChannelType.MESSENGER,
                    "Missing or invalid x-hub-signature-256 header");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));

            if (!java.security.MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
                throw new ChannelException(ChannelType.MESSENGER, "HMAC signature mismatch");
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.MESSENGER,
                    "HMAC verification failed: " + e.getMessage(), e, false);
        }
    }

    @Override
    public List<IncomingMessage> receive(Map<String, String> headers, byte[] body) {
        try {
            JsonNode payload = objectMapper.readTree(body);
            List<IncomingMessage> messages = new ArrayList<>();

            JsonNode entries = payload.get("entry");
            if (entries == null || !entries.isArray()) {
                return messages;
            }

            for (JsonNode entry : entries) {
                JsonNode messagingEvents = entry.get("messaging");
                if (messagingEvents == null || !messagingEvents.isArray()) continue;

                for (JsonNode event : messagingEvents) {
                    String senderId = event.path("sender").path("id").stringValue("");
                    JsonNode message = event.get("message");
                    if (message == null) continue;

                    String text = message.path("text").stringValue("");
                    String mid = message.path("mid").stringValue("");

                    if (!text.isEmpty()) {
                        messages.add(new IncomingMessage(
                                ChannelType.MESSENGER,
                                senderId,
                                Optional.empty(),
                                text,
                                senderId,
                                mid,
                                Instant.now()
                        ));
                    }
                }
            }

            return messages;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.MESSENGER,
                    "Failed to parse Messenger webhook: " + e.getMessage(), e, false);
        }
    }

    @Override
    public DeliveryReceipt send(OutgoingMessage message) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            ObjectNode recipient = payload.putObject("recipient");
            recipient.put("id", message.recipientId());
            ObjectNode msg = payload.putObject("message");
            msg.put("text", message.text());

            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GRAPH_API))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + pageAccessToken)
                    .timeout(ChannelHttpClient.requestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = ChannelHttpClient.get().send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ChannelException(ChannelType.MESSENGER,
                        "Messenger API returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode() >= 500);
            }

            JsonNode result = objectMapper.readTree(response.body());
            String messageId = result.path("message_id").stringValue(null);

            return new DeliveryReceipt(
                    Optional.ofNullable(messageId),
                    DeliveryReceipt.DeliveryStatus.SENT,
                    Instant.now()
            );
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.MESSENGER,
                    "Failed to send message: " + e.getMessage(), e, true);
        }
    }
}
