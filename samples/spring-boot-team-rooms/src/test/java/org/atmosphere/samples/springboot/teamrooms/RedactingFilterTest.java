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
package org.atmosphere.samples.springboot.teamrooms;

import java.time.Instant;

import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The moderation contract of {@link RedactingFilter}. These assert the rewrite actually
 * happens on the outbound value — a filter that returns the message untouched would pass a
 * "no exception thrown" test, which is why every case here inspects the returned payload.
 */
class RedactingFilterTest {

    private final RedactingFilter filter = new RedactingFilter();

    private static Message chat(String text) {
        return new Message("build", "alice", text, Instant.parse("2026-08-28T10:00:00Z"));
    }

    private Message filtered(String text) {
        BroadcastFilter.BroadcastAction a = filter.filter("/atmosphere/rooms/build", chat(text), chat(text));
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, a.action());
        return assertInstanceOf(Message.class, a.message());
    }

    @Test
    void redactsABearerToken() {
        Message out = filtered("here is bearer abcdef0123456789 for you");
        assertTrue(out.text().contains(RedactingFilter.REDACTED), "token survived: " + out.text());
        assertTrue(!out.text().contains("abcdef0123456789"), "raw token still on the wire: " + out.text());
    }

    @Test
    void redactsAnAwsAccessKeyId() {
        Message out = filtered("key AKIAIOSFODNN7EXAMPLE oops");
        assertTrue(out.text().contains(RedactingFilter.REDACTED), "AWS key survived: " + out.text());
    }

    @Test
    void redactsALongHexSecret() {
        Message out = filtered("sig 0123456789abcdef0123456789abcdef");
        assertTrue(out.text().contains(RedactingFilter.REDACTED), "hex secret survived: " + out.text());
    }

    @Test
    void leavesOrdinaryChatAloneAndDoesNotCopy() {
        Message in = chat("ship it");
        BroadcastFilter.BroadcastAction a = filter.filter("/atmosphere/rooms/build", in, in);
        assertSame(in, a.message(), "an unmodified message should pass through untouched");
    }

    @Test
    void preservesRoomAuthorAndTimestampWhileRedacting() {
        Message out = filtered("bearer abcdef0123456789");
        assertEquals("build", out.room());
        assertEquals("alice", out.author());
        assertEquals(Instant.parse("2026-08-28T10:00:00Z"), out.at());
    }

    @Test
    void toleratesANonChatPayload() {
        BroadcastFilter.BroadcastAction a = filter.filter("/x", "raw", "raw");
        assertEquals("raw", a.message(), "non-Message payloads must pass through");
    }

    // ── The shapes that actually reach the wire ────────────────────────────
    // The 2026-08-28 sweep drove this sample over a real WebSocket and got the
    // secret back VERBATIM, despite every test above passing. Cause:
    // ManagedAtmosphereHandler encodes a @Message return value BEFORE the
    // broadcast filters run and delivers a RawMessage wrapping the JSON string,
    // so a filter matching only the domain type never fires. These cases pin the
    // encoded shapes; without them the filter is decorative on the managed path.

    @Test
    void redactsTheEncodedJsonString() {
        String encoded = "{\"room\":\"build\",\"author\":\"alice\","
                + "\"text\":\"bearer abcdef0123456789 leak\"}";
        BroadcastFilter.BroadcastAction a = filter.filter("/atmosphere/rooms/build", encoded, encoded);
        String out = assertInstanceOf(String.class, a.message());
        assertTrue(out.contains(RedactingFilter.REDACTED), "encoded JSON survived unredacted: " + out);
        assertFalse(out.contains("abcdef0123456789"), "raw token still on the wire: " + out);
    }

    @Test
    void redactsInsideARawMessageWrapper() {
        String encoded = "{\"text\":\"bearer abcdef0123456789 leak\"}";
        BroadcastFilter.BroadcastAction a = filter.filter(
                "/atmosphere/rooms/build", encoded, new RawMessage(encoded));
        RawMessage out = assertInstanceOf(RawMessage.class, a.message());
        String inner = String.valueOf(out.message());
        assertTrue(inner.contains(RedactingFilter.REDACTED), "RawMessage payload survived: " + inner);
        assertFalse(inner.contains("abcdef0123456789"));
    }

    @Test
    void leavesACleanEncodedStringIdentical() {
        String encoded = "{\"text\":\"ship it\"}";
        BroadcastFilter.BroadcastAction a = filter.filter("/x", encoded, encoded);
        assertSame(encoded, a.message(), "a clean payload must pass through untouched");
    }
}
