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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.atmosphere.channels.ChannelException;
import org.atmosphere.channels.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class SlackChannelTest {

    private static final String SIGNING_SECRET = "test-signing-secret-abc123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Real Slack Events API payload fixture
    private static final String EVENT_PAYLOAD = """
            {
              "token": "deprecated-verification-token",
              "team_id": "T12345",
              "event": {
                "type": "message",
                "user": "U98765",
                "text": "Hello from Slack!",
                "channel": "C11111",
                "ts": "1710000000.000100"
              },
              "type": "event_callback",
              "event_id": "Ev123456"
            }""";

    private SlackChannel channel;

    @BeforeEach
    void setUp() {
        channel = new SlackChannel("xoxb-fake-token", SIGNING_SECRET, MAPPER);
    }

    @Test
    void verifySignature_validHmac() throws Exception {
        var body = EVENT_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var timestamp = String.valueOf(Instant.now().getEpochSecond());
        var signature = computeSlackSignature(timestamp, body);

        var headers = Map.of(
                "x-slack-request-timestamp", timestamp,
                "x-slack-signature", signature);

        assertDoesNotThrow(() -> channel.verifySignature(headers, body));
    }

    @Test
    void verifySignature_invalidSignature() {
        var body = EVENT_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var timestamp = String.valueOf(Instant.now().getEpochSecond());

        var headers = Map.of(
                "x-slack-request-timestamp", timestamp,
                "x-slack-signature", "v0=0000000000000000000000000000000000000000000000000000000000000000");

        var ex = assertThrows(ChannelException.class,
                () -> channel.verifySignature(headers, body));
        assertTrue(ex.getMessage().contains("mismatch"));
    }

    @Test
    void verifySignature_rejectsOldTimestamp() {
        var body = EVENT_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var oldTimestamp = String.valueOf(Instant.now().getEpochSecond() - 600);

        var headers = Map.of(
                "x-slack-request-timestamp", oldTimestamp,
                "x-slack-signature", "v0=anything");

        var ex = assertThrows(ChannelException.class,
                () -> channel.verifySignature(headers, body));
        assertTrue(ex.getMessage().contains("old") || ex.getMessage().contains("replay"));
    }

    @Test
    void verifySignature_rejectsFutureTimestamp() {
        var body = EVENT_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var futureTimestamp = String.valueOf(Instant.now().getEpochSecond() + 600);

        var headers = Map.of(
                "x-slack-request-timestamp", futureTimestamp,
                "x-slack-signature", "v0=anything");

        var ex = assertThrows(ChannelException.class,
                () -> channel.verifySignature(headers, body));
        assertTrue(ex.getMessage().contains("old") || ex.getMessage().contains("replay"));
    }

    @Test
    void verifySignature_missingHeaders() {
        assertThrows(ChannelException.class,
                () -> channel.verifySignature(Map.of(), new byte[0]));
    }

    @Test
    void receive_parsesMessageEvent() {
        var body = EVENT_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var messages = channel.receive(Map.of(), body);

        assertEquals(1, messages.size());
        var msg = messages.getFirst();
        assertEquals(ChannelType.SLACK, msg.channelType());
        assertEquals("U98765", msg.senderId());
        assertEquals("Hello from Slack!", msg.text());
        assertEquals("C11111", msg.conversationId());
    }

    @Test
    void receive_skipsBotMessages() {
        var payload = """
                {
                  "event": {
                    "type": "message",
                    "bot_id": "B12345",
                    "text": "Bot message",
                    "channel": "C11111",
                    "ts": "1710000000.000200"
                  },
                  "type": "event_callback"
                }""";

        var messages = channel.receive(Map.of(), payload.getBytes(StandardCharsets.UTF_8));
        assertTrue(messages.isEmpty());
    }

    @Test
    void receive_handlesUrlVerification() {
        var payload = """
                {"type": "url_verification", "challenge": "abc123"}""";

        var messages = channel.receive(Map.of(), payload.getBytes(StandardCharsets.UTF_8));
        assertTrue(messages.isEmpty());
    }

    private String computeSlackSignature(String timestamp, byte[] body) throws Exception {
        var baseString = "v0:" + timestamp + ":" + new String(body, StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(), "HmacSHA256"));
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(baseString.getBytes()));
    }
}
