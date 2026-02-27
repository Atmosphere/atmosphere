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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ContentSafetyFilterTest {

    private BroadcastAction sendToken(ContentSafetyFilter filter, String data, String sessionId, long seq) {
        var msg = new AiStreamMessage("token", data, sessionId, seq, null, null);
        var raw = new RawMessage(msg.toJson());
        return filter.filter("b1", raw, raw);
    }

    private BroadcastAction sendComplete(ContentSafetyFilter filter, String sessionId, long seq) {
        var msg = new AiStreamMessage("complete", null, sessionId, seq, null, null);
        var raw = new RawMessage(msg.toJson());
        return filter.filter("b1", raw, raw);
    }

    @Test
    public void testSafeContentPassesThrough() throws Exception {
        var filter = new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(Set.of("danger")));

        // Token with sentence boundary
        var result = sendToken(filter, "This is safe content.", "s1", 1);
        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());

        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertEquals("This is safe content.", parsed.data());
    }

    @Test
    public void testUnsafeContentAbortsStream() throws Exception {
        var filter = new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(Set.of("danger")));

        var result = sendToken(filter, "This is danger zone.", "s1", 1);
        assertEquals(BroadcastAction.ACTION.SKIP, result.action());

        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertTrue(parsed.isError());
        assertTrue(parsed.data().contains("Content blocked"));
    }

    @Test
    public void testRedactingCheckerReplacesContent() throws Exception {
        var checker = ContentSafetyFilter.redactingChecker(Set.of("badword"), "***");
        var filter = new ContentSafetyFilter(checker);

        var result = sendToken(filter, "This has a badword in it.", "s1", 1);
        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());

        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertFalse(parsed.data().contains("badword"));
        assertTrue(parsed.data().contains("***"));
    }

    @Test
    public void testBufferingUntilSentenceBoundary() {
        var filter = new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(Set.of("danger")));

        // No sentence boundary — buffered
        var result = sendToken(filter, "This is ", "s1", 1);
        assertEquals(BroadcastAction.ACTION.ABORT, result.action());
    }

    @Test
    public void testBufferFlushedOnComplete() throws Exception {
        var filter = new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(Set.of("danger")));

        // Buffer a safe token
        sendToken(filter, "This is safe", "s1", 1);

        // Complete should flush the buffer as a proper "token" message (not embed in complete.data)
        // The actual complete is deferred via broadcaster (not available in unit tests)
        var result = sendComplete(filter, "s1", 2);
        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertTrue(parsed.isToken());
        assertEquals("This is safe", parsed.data());
    }

    @Test
    public void testUnsafeBufferedContentOnComplete() throws Exception {
        var filter = new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(Set.of("danger")));

        // Buffer unsafe content
        sendToken(filter, "This is danger", "s1", 1);

        // Complete should detect the unsafe content
        var result = sendComplete(filter, "s1", 2);
        assertEquals(BroadcastAction.ACTION.SKIP, result.action());

        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertTrue(parsed.isError());
        assertTrue(parsed.data().contains("Content blocked"));
    }

    @Test
    public void testKeywordCheckerIsCaseInsensitive() throws Exception {
        var filter = new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(Set.of("danger")));

        var result = sendToken(filter, "This has DANGER here.", "s1", 1);
        assertEquals(BroadcastAction.ACTION.SKIP, result.action());
    }

    @Test
    public void testRedactingCheckerIsCaseInsensitive() throws Exception {
        var checker = ContentSafetyFilter.redactingChecker(Set.of("badword"), "***");
        var filter = new ContentSafetyFilter(checker);

        var result = sendToken(filter, "Has BADWORD here.", "s1", 1);
        var raw = (RawMessage) result.message();
        var parsed = AiStreamMessage.parse((String) raw.message());
        assertFalse(parsed.data().contains("BADWORD"));
        assertTrue(parsed.data().contains("***"));
    }

    @Test
    public void testPassesThroughMetadata() {
        var filter = new ContentSafetyFilter(ContentSafetyFilter.keywordChecker(Set.of("danger")));
        var msg = new AiStreamMessage("metadata", null, "s1", 1, "model", "gpt-4");
        var raw = new RawMessage(msg.toJson());
        var result = filter.filter("b1", raw, raw);

        assertEquals(BroadcastAction.ACTION.CONTINUE, result.action());
        assertSame(raw, result.message());
    }

    @Test
    public void testCustomSafetyChecker() throws Exception {
        // A checker that flags any text longer than 20 chars as unsafe
        ContentSafetyFilter.SafetyChecker lengthChecker = text ->
                text.length() > 20
                        ? new ContentSafetyFilter.SafetyResult.Unsafe("Too long")
                        : new ContentSafetyFilter.SafetyResult.Safe();

        var filter = new ContentSafetyFilter(lengthChecker);

        // Short text — safe
        var result1 = sendToken(filter, "Short.", "s1", 1);
        assertEquals(BroadcastAction.ACTION.CONTINUE, result1.action());

        // Long text — unsafe
        var result2 = sendToken(filter, "This is a very long sentence that exceeds the limit.", "s2", 1);
        assertEquals(BroadcastAction.ACTION.SKIP, result2.action());
    }
}
