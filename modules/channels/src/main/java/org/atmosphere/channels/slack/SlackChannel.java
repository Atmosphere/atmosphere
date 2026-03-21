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
package org.atmosphere.channels.slack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Slack Events API channel adapter with Block Kit rendering.
 * <p>
 * Verification: HMAC-SHA256 with Slack's v0 scheme.
 * Rendering: Block Kit sections with mrkdwn text.
 * Supports progressive message editing via {@link #updateMessage} for streaming.
 * <p>
 * Ported from <a href="https://github.com/dravr-ai/dravr-canot">Canot</a>'s
 * Rust Slack adapter.
 */
public class SlackChannel implements MessagingChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);
    private static final String SLACK_API = "https://slack.com/api/";
    private static final long MAX_TIMESTAMP_AGE_SECS = 300; // 5 minutes

    private final String botToken;
    private final String signingSecret;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SlackChannel(String botToken, String signingSecret, ObjectMapper objectMapper) {
        this.botToken = botToken;
        this.signingSecret = signingSecret;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.SLACK;
    }

    @Override
    public String webhookPath() {
        return "/webhook/slack";
    }

    @Override
    public int maxMessageLength() {
        return 40_000;
    }

    @Override
    public void verifySignature(Map<String, String> headers, byte[] body) {
        String timestamp = headers.get("x-slack-request-timestamp");
        String signature = headers.get("x-slack-signature");

        if (timestamp == null || signature == null) {
            throw new ChannelException(ChannelType.SLACK,
                    "Missing x-slack-request-timestamp or x-slack-signature header");
        }

        // Replay protection
        long ts = Long.parseLong(timestamp);
        long age = Instant.now().getEpochSecond() - ts;
        if (age > MAX_TIMESTAMP_AGE_SECS) {
            throw new ChannelException(ChannelType.SLACK,
                    "Timestamp too old (" + age + "s), possible replay attack");
        }

        // HMAC-SHA256 v0 verification
        String baseString = "v0:" + timestamp + ":" + new String(body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(), "HmacSHA256"));
            String expected = "v0=" + HexFormat.of().formatHex(mac.doFinal(baseString.getBytes()));

            if (!expected.equals(signature)) {
                throw new ChannelException(ChannelType.SLACK, "Signature mismatch");
            }
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.SLACK,
                    "HMAC verification failed: " + e.getMessage(), e, false);
        }
    }

    @Override
    public List<IncomingMessage> receive(Map<String, String> headers, byte[] body) {
        try {
            JsonNode event = objectMapper.readTree(body);

            // Handle Slack URL verification challenge
            if ("url_verification".equals(event.path("type").stringValue())) {
                return List.of();
            }

            JsonNode innerEvent = event.get("event");
            if (innerEvent == null || !"message".equals(innerEvent.path("type").stringValue())) {
                return List.of();
            }

            // Ignore bot messages (prevents loops)
            if (innerEvent.has("bot_id") || innerEvent.has("subtype")) {
                return List.of();
            }

            String userId = innerEvent.path("user").stringValue("");
            String text = innerEvent.path("text").stringValue("");
            String channel = innerEvent.path("channel").stringValue("");
            String ts = innerEvent.path("ts").stringValue("");

            return List.of(new IncomingMessage(
                    ChannelType.SLACK,
                    userId,
                    Optional.empty(),
                    text,
                    channel,
                    ts,
                    Instant.now()
            ));
        } catch (Exception e) {
            throw new ChannelException(ChannelType.SLACK,
                    "Failed to parse Slack event: " + e.getMessage(), e, false);
        }
    }

    @Override
    public DeliveryReceipt send(OutgoingMessage message) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("channel", message.recipientId());

            // Block Kit rendering
            ArrayNode blocks = payload.putArray("blocks");
            ObjectNode section = blocks.addObject();
            section.put("type", "section");
            ObjectNode textObj = section.putObject("text");
            textObj.put("type", "mrkdwn");
            textObj.put("text", message.text());

            // Thread reply
            message.replyTo().ifPresent(ts -> payload.put("thread_ts", ts));

            String responseBody = callSlackApi("chat.postMessage", payload);
            JsonNode result = objectMapper.readTree(responseBody);

            String ts = result.path("ts").stringValue(null);

            return new DeliveryReceipt(
                    Optional.ofNullable(ts),
                    DeliveryReceipt.DeliveryStatus.SENT,
                    Instant.now()
            );
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.SLACK,
                    "Failed to send message: " + e.getMessage(), e, true);
        }
    }

    /**
     * Update an existing message (for progressive streaming on Slack).
     * Slack supports {@code chat.update} to edit messages in place.
     *
     * @param channel   the Slack channel
     * @param ts        the message timestamp (ID) to update
     * @param newText   updated text
     */
    public void updateMessage(String channel, String ts, String newText) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("channel", channel);
            payload.put("ts", ts);

            ArrayNode blocks = payload.putArray("blocks");
            ObjectNode section = blocks.addObject();
            section.put("type", "section");
            ObjectNode textObj = section.putObject("text");
            textObj.put("type", "mrkdwn");
            textObj.put("text", newText);

            callSlackApi("chat.update", payload);
        } catch (Exception e) {
            log.warn("Failed to update Slack message: {}", e.getMessage());
        }
    }

    private String callSlackApi(String method, ObjectNode payload) {
        try {
            String url = SLACK_API + method;
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + botToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ChannelException(ChannelType.SLACK,
                        "Slack API returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode() >= 500);
            }

            return response.body();
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException(ChannelType.SLACK,
                    "Slack API call failed: " + e.getMessage(), e, true);
        }
    }
}
