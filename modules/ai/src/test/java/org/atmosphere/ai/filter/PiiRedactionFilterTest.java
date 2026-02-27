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
package org.atmosphere.ai.filter;

import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class PiiRedactionFilterTest {

    private final PiiRedactionFilter filter = new PiiRedactionFilter();

    private BroadcastAction sendToken(String data, String sessionId, long seq) {
        var msg = new AiStreamMessage("token", data, sessionId, seq, null, null);
        var raw = new RawMessage(msg.toJson());
        return filter.filter("b1", raw, raw);
    }

    private BroadcastAction sendComplete(String sessionId, long seq) {
        var msg = new AiStreamMessage("complete", null, sessionId, seq, null, null);
        var raw = new RawMessage(msg.toJson());
        return filter.filter("b1", raw, raw);
    }

    @Test
    public void testRedactsEmail() {
        assertEquals("[REDACTED]", filter.redact("user@example.com"));
    }

    @Test
    public void testRedactsPhone() {
        assertEquals("[REDACTED]", filter.redact("555-123-4567"));
        assertEquals("[REDACTED]", filter.redact("(555) 123-4567"));
        assertEquals("[REDACTED]", filter.redact("+1 555 123 4567"));
    }

    @Test
    public void testRedactsSsn() {
        assertEquals("[REDACTED]", filter.redact("123-45-6789"));
    }

    @Test
    public void testRedactsCreditCard() {
        assertEquals("[REDACTED]", filter.redact("4111 1111 1111 1111"));
    }

    @Test
    public void testRedactsMultiplePatterns() {
        var text = "Contact user@example.com or call 555-123-4567.";
        var result = filter.redact(text);
        assertFalse(result.contains("user@example.com"));
        assertFalse(result.contains("555-123-4567"));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    public void testNoRedactionOnCleanText() {
        assertEquals("Hello world", filter.redact("Hello world"));
    }

    @Test
    public void testTokenBufferingUntilSentenceBoundary() throws Exception {
        // First token: no sentence boundary — should be ABORT (buffered)
        var result1 = sendToken("My email is ", "s1", 1);
        assertEquals(BroadcastAction.ACTION.ABORT, result1.action());

        // Second token with sentence boundary — should flush with redaction
        var result2 = sendToken("user@example.com.", "s1", 2);
        assertEquals(BroadcastAction.ACTION.CONTINUE, result2.action());

        var raw = (RawMessage) result2.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertFalse(parsed.data().contains("user@example.com"));
        assertTrue(parsed.data().contains("[REDACTED]"));
    }

    @Test
    public void testFlushOnComplete() throws Exception {
        // Buffer tokens without sentence boundary (no . ! ? or newline)
        // Note: email contains dots which count as sentence boundaries,
        // so use a phone number instead for this buffering test
        var tokenResult = sendToken("Call me at 555-123-4567", "s1", 1);
        assertEquals(BroadcastAction.ACTION.ABORT, tokenResult.action(), "Token should be buffered (ABORT)");

        // Complete should flush the buffer as a proper "token" message (not embed in complete.data)
        // The actual complete is deferred via broadcaster (not available in unit tests)
        var result = sendComplete("s1", 2);
        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());

        // Flushed text arrives as "token" type to maintain protocol invariant
        assertTrue(parsed.isToken());
        assertNotNull(parsed.data());
        assertFalse(parsed.data().contains("555-123-4567"));
        assertTrue(parsed.data().contains("[REDACTED]"));
    }

    @Test
    public void testCustomPattern() throws Exception {
        var customFilter = new PiiRedactionFilter()
                .addPattern("custom-id", Pattern.compile("ID-\\d{6}"));

        var text = "Your ID is ID-123456.";
        var result = customFilter.redact(text);
        assertFalse(result.contains("ID-123456"));
    }

    @Test
    public void testCustomReplacement() {
        var customFilter = new PiiRedactionFilter("***");
        assertEquals("***", customFilter.redact("user@example.com"));
    }

    @Test
    public void testPassesThroughMetadata() {
        var msg = new AiStreamMessage("metadata", null, "s1", 1, "model", "gpt-4");
        var raw = new RawMessage(msg.toJson());
        var result = filter.filter("b1", raw, raw);

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertSame(raw, result.message());
    }

    @Test
    public void testPassesThroughProgress() {
        var msg = new AiStreamMessage("progress", "Thinking...", "s1", 1, null, null);
        var raw = new RawMessage(msg.toJson());
        var result = filter.filter("b1", raw, raw);

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertSame(raw, result.message());
    }

    @Test
    public void testRemovePattern() {
        var customFilter = new PiiRedactionFilter();
        customFilter.removePattern("email");
        assertEquals("user@example.com", customFilter.redact("user@example.com"));
    }
}
