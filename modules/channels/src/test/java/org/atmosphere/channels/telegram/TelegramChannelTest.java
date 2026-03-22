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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.atmosphere.channels.ChannelException;
import org.atmosphere.channels.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class TelegramChannelTest {

    private static final String WEBHOOK_SECRET = "test-secret-token-123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Real Telegram Update payload fixture
    private static final String UPDATE_PAYLOAD = """
            {
              "update_id": 123456789,
              "message": {
                "message_id": 42,
                "from": {
                  "id": 987654321,
                  "is_bot": false,
                  "first_name": "Jean-Francois",
                  "username": "jfarcand"
                },
                "chat": {
                  "id": 987654321,
                  "first_name": "Jean-Francois",
                  "type": "private"
                },
                "date": 1710000000,
                "text": "Hello from Telegram!"
              }
            }""";

    private TelegramChannel channel;

    @BeforeEach
    void setUp() {
        channel = new TelegramChannel("fake-bot-token", WEBHOOK_SECRET, MAPPER);
    }

    @Test
    void verifySignature_validSecret() {
        var headers = Map.of("x-telegram-bot-api-secret-token", WEBHOOK_SECRET);
        assertDoesNotThrow(() -> channel.verifySignature(headers, new byte[0]));
    }

    @Test
    void verifySignature_invalidSecret() {
        var headers = Map.of("x-telegram-bot-api-secret-token", "wrong-secret");
        var ex = assertThrows(ChannelException.class,
                () -> channel.verifySignature(headers, new byte[0]));
        assertTrue(ex.getMessage().contains("mismatch"));
    }

    @Test
    void verifySignature_missingHeader() {
        var ex = assertThrows(ChannelException.class,
                () -> channel.verifySignature(Map.of(), new byte[0]));
        assertTrue(ex.getMessage().contains("Missing"));
    }

    @Test
    void receive_parsesTextMessage() {
        var headers = Map.of("x-telegram-bot-api-secret-token", WEBHOOK_SECRET);
        var body = UPDATE_PAYLOAD.getBytes(StandardCharsets.UTF_8);

        var messages = channel.receive(headers, body);

        assertEquals(1, messages.size());
        var msg = messages.getFirst();
        assertEquals(ChannelType.TELEGRAM, msg.channelType());
        assertEquals("987654321", msg.senderId());
        assertEquals("Jean-Francois", msg.senderName().orElse(null));
        assertEquals("Hello from Telegram!", msg.text());
        assertEquals("987654321", msg.conversationId());
        assertEquals("42", msg.messageId());
    }

    @Test
    void receive_skipsNonTextMessage() {
        var payload = """
                {
                  "update_id": 123456789,
                  "message": {
                    "message_id": 43,
                    "from": {"id": 123, "first_name": "Test"},
                    "chat": {"id": 123, "type": "private"},
                    "sticker": {"file_id": "abc123"}
                  }
                }""";

        var messages = channel.receive(Map.of(), payload.getBytes(StandardCharsets.UTF_8));
        assertTrue(messages.isEmpty());
    }

    @Test
    void receive_skipsUpdateWithoutMessage() {
        var payload = """
                {"update_id": 123456789}""";

        var messages = channel.receive(Map.of(), payload.getBytes(StandardCharsets.UTF_8));
        assertTrue(messages.isEmpty());
    }

    @Test
    void channelType() {
        assertEquals(ChannelType.TELEGRAM, channel.channelType());
    }

    @Test
    void maxMessageLength() {
        assertEquals(4096, channel.maxMessageLength());
    }
}
