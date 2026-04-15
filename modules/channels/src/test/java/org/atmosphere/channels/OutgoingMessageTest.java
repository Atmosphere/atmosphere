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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutgoingMessageTest {

    @Test
    void fullConstructorSetsAllFields() {
        var msg = new OutgoingMessage("chat-123", "Hello!",
                Optional.of("msg-99"), Optional.of("MarkdownV2"));
        assertEquals("chat-123", msg.recipientId());
        assertEquals("Hello!", msg.text());
        assertEquals(Optional.of("msg-99"), msg.replyTo());
        assertEquals(Optional.of("MarkdownV2"), msg.parseMode());
    }

    @Test
    void convenienceConstructorSetsDefaultOptionals() {
        var msg = new OutgoingMessage("chat-456", "Hi there");
        assertEquals("chat-456", msg.recipientId());
        assertEquals("Hi there", msg.text());
        assertEquals(Optional.empty(), msg.replyTo());
        assertEquals(Optional.empty(), msg.parseMode());
    }

    @Test
    void emptyTextIsAllowed() {
        var msg = new OutgoingMessage("r1", "");
        assertEquals("", msg.text());
    }

    @Test
    void equalityForSameValues() {
        var a = new OutgoingMessage("r1", "text", Optional.empty(), Optional.empty());
        var b = new OutgoingMessage("r1", "text");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equalityWithReplyTo() {
        var a = new OutgoingMessage("r1", "text", Optional.of("m1"), Optional.empty());
        var b = new OutgoingMessage("r1", "text", Optional.of("m1"), Optional.empty());
        assertEquals(a, b);
    }

    @Test
    void convenienceConstructorIsEquivalentToFullWithEmptyOptionals() {
        var convenience = new OutgoingMessage("r1", "msg");
        var full = new OutgoingMessage("r1", "msg", Optional.empty(), Optional.empty());
        assertEquals(convenience, full);
    }
}
