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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.HexFormat;
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
 * Discord Bot API channel adapter with Ed25519 signature verification
 * and embed-based rendering.
 * <p>
 * Supports progressive message editing via {@link #editMessage} for streaming.
 */
public class DiscordChannel implements MessagingChannel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);
    private static final String DISCORD_API = "https://discord.com/api/v10";

    private final String botToken;
    private final String publicKeyHex;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DiscordChannel(String botToken, String publicKeyHex, ObjectMapper objectMapper) {
        this.botToken = botToken;
        this.publicKeyHex = publicKeyHex;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.DISCORD;
    }

    @Override
    public String webhookPath() {
        return "/webhook/discord";
    }

    @Override
    public int maxMessageLength() {
        return 2000;
    }

    @Override
    public void verifySignature(Map<String, String> headers, byte[] body) {
        String signatureHex = headers.get("x-signature-ed25519");
        String timestamp = headers.get("x-signature-timestamp");

        if (signatureHex == null || timestamp == null) {
            throw new ChannelException(ChannelType.DISCORD,
                    "Missing x-signature-ed25519 or x-signature-timestamp header");
        }

        try {
            byte[] publicKeyBytes = HexFormat.of().parseHex(publicKeyHex);

            // Ed25519 public key: prepend the OID prefix for X509 encoding
            byte[] x509Prefix = HexFormat.of().parseHex("302a300506032b6570032100");
            byte[] x509Key = new byte[x509Prefix.length + publicKeyBytes.length];
            System.arraycopy(x509Prefix, 0, x509Key, 0, x509Prefix.length);
            System.arraycopy(publicKeyBytes, 0, x509Key, x509Prefix.length, publicKeyBytes.length);

            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(x509Key));

            byte[] message = (timestamp + new String(body)).getBytes();
            byte[] signatureBytes = HexFormat.of().parseHex(signatureHex);

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(message);

            if (!sig.verify(signatureBytes)) {
                throw new ChannelException(ChannelType.DISCORD, "Ed25519 signature mismatch");
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.DISCORD,
                    "Ed25519 verification failed: " + e.getMessage(), e, false);
        }
    }

    @Override
    public List<IncomingMessage> receive(Map<String, String> headers, byte[] body) {
        try {
            JsonNode interaction = objectMapper.readTree(body);
            int type = interaction.path("type").intValue();

            // Type 1 = PING (verification), Type 2 = APPLICATION_COMMAND, Type 3 = MESSAGE_COMPONENT
            if (type == 1) {
                return List.of(); // Ping — handled by returning {"type": 1}
            }

            // Message from a channel (type 2 or 3)
            String channelId = interaction.path("channel_id").stringValue("");
            String userId = interaction.path("member").path("user").path("id").stringValue(
                    interaction.path("user").path("id").stringValue(""));
            String userName = interaction.path("member").path("user").path("username").stringValue(null);

            String text;
            if (type == 2) {
                // Slash command — extract from options
                text = interaction.path("data").path("name").stringValue("");
                JsonNode options = interaction.path("data").path("options");
                if (options.isArray() && !options.isEmpty()) {
                    text = options.get(0).path("value").stringValue(text);
                }
            } else {
                text = interaction.path("data").path("custom_id").stringValue("");
            }

            String interactionId = interaction.path("id").stringValue("");

            return List.of(new IncomingMessage(
                    ChannelType.DISCORD,
                    userId,
                    Optional.ofNullable(userName),
                    text,
                    channelId,
                    interactionId,
                    Instant.now()
            ));
        } catch (Exception e) {
            throw new ChannelException(ChannelType.DISCORD,
                    "Failed to parse Discord interaction: " + e.getMessage(), e, false);
        }
    }

    @Override
    public DeliveryReceipt send(OutgoingMessage message) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("content", message.text());

            String url = DISCORD_API + "/channels/" + message.recipientId() + "/messages";
            String responseBody = callDiscordApi(url, payload);
            JsonNode result = objectMapper.readTree(responseBody);

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
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Failed to edit Discord message: {}", e.getMessage());
        }
    }

    private String callDiscordApi(String url, ObjectNode payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bot " + botToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ChannelException(ChannelType.DISCORD,
                        "Discord API returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode() >= 500);
            }

            return response.body();
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.DISCORD,
                    "Discord API call failed: " + e.getMessage(), e, true);
        }
    }
}
