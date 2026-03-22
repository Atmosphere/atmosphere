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

import java.nio.charset.StandardCharsets;
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

class WhatsAppChannelTest {

    private static final String APP_SECRET = "whatsapp-app-secret-xyz";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Real WhatsApp Cloud API webhook payload fixture
    private static final String WEBHOOK_PAYLOAD = """
            {
              "object": "whatsapp_business_account",
              "entry": [{
                "id": "123456789",
                "changes": [{
                  "value": {
                    "messaging_product": "whatsapp",
                    "metadata": {"display_phone_number": "15551234567", "phone_number_id": "987654"},
                    "contacts": [{"profile": {"name": "Jean-Francois"}, "wa_id": "15559876543"}],
                    "messages": [{
                      "from": "15559876543",
                      "id": "wamid.abc123",
                      "timestamp": "1710000000",
                      "text": {"body": "Hello from WhatsApp!"},
                      "type": "text"
                    }]
                  },
                  "field": "messages"
                }]
              }]
            }""";

    private WhatsAppChannel channel;

    @BeforeEach
    void setUp() {
        channel = new WhatsAppChannel("987654", "fake-access-token", APP_SECRET, MAPPER);
    }

    @Test
    void verifySignature_validHmac() throws Exception {
        var body = WEBHOOK_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var signature = computeMetaSignature(body);

        var headers = Map.of("x-hub-signature-256", signature);
        assertDoesNotThrow(() -> channel.verifySignature(headers, body));
    }

    @Test
    void verifySignature_invalidSignature() {
        var body = WEBHOOK_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var headers = Map.of("x-hub-signature-256", "sha256=0000000000000000000000000000000000000000000000000000000000000000");

        var ex = assertThrows(ChannelException.class,
                () -> channel.verifySignature(headers, body));
        assertTrue(ex.getMessage().contains("mismatch"));
    }

    @Test
    void verifySignature_missingHeader() {
        assertThrows(ChannelException.class,
                () -> channel.verifySignature(Map.of(), new byte[0]));
    }

    @Test
    void verifySignature_invalidPrefix() {
        var headers = Map.of("x-hub-signature-256", "md5=abc123");
        assertThrows(ChannelException.class,
                () -> channel.verifySignature(headers, new byte[0]));
    }

    @Test
    void receive_parsesTextMessage() {
        var body = WEBHOOK_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        var messages = channel.receive(Map.of(), body);

        assertEquals(1, messages.size());
        var msg = messages.getFirst();
        assertEquals(ChannelType.WHATSAPP, msg.channelType());
        assertEquals("15559876543", msg.senderId());
        assertEquals("Jean-Francois", msg.senderName().orElse(null));
        assertEquals("Hello from WhatsApp!", msg.text());
        assertEquals("wamid.abc123", msg.messageId());
    }

    @Test
    void receive_skipsStatusUpdates() {
        var payload = """
                {
                  "entry": [{
                    "changes": [{
                      "value": {
                        "statuses": [{"id": "wamid.xyz", "status": "delivered"}]
                      }
                    }]
                  }]
                }""";

        var messages = channel.receive(Map.of(), payload.getBytes(StandardCharsets.UTF_8));
        assertTrue(messages.isEmpty());
    }

    private String computeMetaSignature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
