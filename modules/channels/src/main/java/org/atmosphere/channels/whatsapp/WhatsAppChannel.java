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
package org.atmosphere.channels.whatsapp;

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
 * WhatsApp Business Cloud API channel adapter.
 * <p>
 * Verification: HMAC-SHA256 via {@code x-hub-signature-256} header (Meta platform standard).
 * Rendering: WhatsApp Cloud API JSON payloads.
 */
public class WhatsAppChannel implements MessagingChannel {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChannel.class);
    private static final String GRAPH_API = "https://graph.facebook.com/v21.0";

    private final String phoneNumberId;
    private final String accessToken;
    private final String appSecret;
    private final ObjectMapper objectMapper;

    public WhatsAppChannel(String phoneNumberId, String accessToken, String appSecret,
                           ObjectMapper objectMapper) {
        this.phoneNumberId = phoneNumberId;
        this.accessToken = accessToken;
        this.appSecret = appSecret;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.WHATSAPP;
    }

    @Override
    public String webhookPath() {
        return "/webhook/whatsapp";
    }

    @Override
    public int maxMessageLength() {
        return 4096;
    }

    @Override
    public void verifySignature(Map<String, String> headers, byte[] body) {
        String signature = headers.get("x-hub-signature-256");
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new ChannelException(ChannelType.WHATSAPP,
                    "Missing or invalid x-hub-signature-256 header");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));

            if (!java.security.MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
                throw new ChannelException(ChannelType.WHATSAPP, "HMAC signature mismatch");
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.WHATSAPP,
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
                JsonNode changes = entry.get("changes");
                if (changes == null || !changes.isArray()) {
                    continue;
                }

                for (JsonNode change : changes) {
                    JsonNode value = change.get("value");
                    if (value == null) {
                        continue;
                    }

                    // Skip status updates — only process user messages
                    JsonNode msgs = value.get("messages");
                    if (msgs == null || !msgs.isArray()) {
                        continue;
                    }

                    for (JsonNode msg : msgs) {
                        String from = msg.path("from").stringValue("");
                        String msgId = msg.path("id").stringValue("");
                        String type = msg.path("type").stringValue("");

                        String text;
                        if ("text".equals(type)) {
                            text = msg.path("text").path("body").stringValue("");
                        } else {
                            text = "[" + type + " message]";
                        }

                        String contactName = null;
                        JsonNode contacts = value.get("contacts");
                        if (contacts != null && contacts.isArray() && !contacts.isEmpty()) {
                            contactName = contacts.get(0).path("profile").path("name").stringValue(null);
                        }

                        messages.add(new IncomingMessage(
                                ChannelType.WHATSAPP,
                                from,
                                Optional.ofNullable(contactName),
                                text,
                                from,
                                msgId,
                                Instant.now()
                        ));
                    }
                }
            }

            return messages;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.WHATSAPP,
                    "Failed to parse WhatsApp webhook: " + e.getMessage(), e, false);
        }
    }

    @Override
    public DeliveryReceipt send(OutgoingMessage message) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("messaging_product", "whatsapp");
            payload.put("to", message.recipientId());
            payload.put("type", "text");
            ObjectNode text = payload.putObject("text");
            text.put("body", message.text());

            String url = GRAPH_API + "/" + phoneNumberId + "/messages";
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(ChannelHttpClient.requestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = ChannelHttpClient.get().send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ChannelException(ChannelType.WHATSAPP,
                        "Graph API returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode() >= 500);
            }

            JsonNode result = objectMapper.readTree(response.body());
            String messageId = null;
            JsonNode msgArray = result.get("messages");
            if (msgArray != null && msgArray.isArray() && !msgArray.isEmpty()) {
                messageId = msgArray.get(0).path("id").stringValue(null);
            }

            return new DeliveryReceipt(
                    Optional.ofNullable(messageId),
                    DeliveryReceipt.DeliveryStatus.SENT,
                    Instant.now()
            );
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.WHATSAPP,
                    "Failed to send message: " + e.getMessage(), e, true);
        }
    }
}
