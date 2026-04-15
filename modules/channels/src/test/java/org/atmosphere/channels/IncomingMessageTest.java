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
package org.atmosphere.channels;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link IncomingMessage} record covering field accessors,
 * equality, and hashCode behavior.
 */
class IncomingMessageTest {

    private static final Instant NOW = Instant.parse("2025-01-15T10:30:00Z");

    @Test
    void fieldAccessorsReturnConstructorValues() {
        var msg = new IncomingMessage(
                ChannelType.TELEGRAM, "sender-1", Optional.of("Alice"),
                "Hello", "conv-1", "msg-1", NOW);
        assertEquals(ChannelType.TELEGRAM, msg.channelType());
        assertEquals("sender-1", msg.senderId());
        assertEquals(Optional.of("Alice"), msg.senderName());
        assertEquals("Hello", msg.text());
        assertEquals("conv-1", msg.conversationId());
        assertEquals("msg-1", msg.messageId());
        assertEquals(NOW, msg.timestamp());
    }

    @Test
    void emptySenderNameIsHandled() {
        var msg = new IncomingMessage(
                ChannelType.SLACK, "U1", Optional.empty(),
                "hi", "C1", "M1", NOW);
        assertTrue(msg.senderName().isEmpty());
    }

    @Test
    void equalRecordsAreEqual() {
        var msg1 = new IncomingMessage(
                ChannelType.DISCORD, "U1", Optional.of("Bob"),
                "text", "C1", "M1", NOW);
        var msg2 = new IncomingMessage(
                ChannelType.DISCORD, "U1", Optional.of("Bob"),
                "text", "C1", "M1", NOW);
        assertEquals(msg1, msg2);
        assertEquals(msg1.hashCode(), msg2.hashCode());
    }

    @Test
    void differentFieldsMakeRecordsUnequal() {
        var msg1 = new IncomingMessage(
                ChannelType.DISCORD, "U1", Optional.empty(),
                "text", "C1", "M1", NOW);
        var msg2 = new IncomingMessage(
                ChannelType.SLACK, "U1", Optional.empty(),
                "text", "C1", "M1", NOW);
        assertNotEquals(msg1, msg2);
    }

    @Test
    void allChannelTypesCanBeUsed() {
        for (var ct : ChannelType.values()) {
            var msg = new IncomingMessage(ct, "U1", Optional.empty(),
                    "msg", "C1", "M1", NOW);
            assertEquals(ct, msg.channelType());
        }
    }

    @Test
    void toStringContainsFieldValues() {
        var msg = new IncomingMessage(
                ChannelType.WHATSAPP, "U42", Optional.of("Charlie"),
                "Hi there", "C7", "M99", NOW);
        var str = msg.toString();
        assertTrue(str.contains("WHATSAPP"));
        assertTrue(str.contains("U42"));
    }
}
