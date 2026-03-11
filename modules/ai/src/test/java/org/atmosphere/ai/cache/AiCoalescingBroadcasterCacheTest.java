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
package org.atmosphere.ai.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.atmosphere.ai.filter.AiStreamMessage;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AiCoalescingBroadcasterCacheTest {

    private static RawMessage streamingTextMsg(String sessionId, String data, long seq) {
        var msg = new AiStreamMessage("streaming-text", data, sessionId, seq, null, null);
        return new RawMessage(msg.toJson());
    }

    private static RawMessage completeMsg(String sessionId, long seq) {
        var msg = new AiStreamMessage("complete", null, sessionId, seq, null, null);
        return new RawMessage(msg.toJson());
    }

    private static RawMessage metadataMsg(String sessionId, long seq) {
        var msg = new AiStreamMessage("metadata", null, sessionId, seq, "model", "gpt-4");
        return new RawMessage(msg.toJson());
    }

    private AiCoalescingBroadcasterCache createCache(List<Object> delegateMessages) {
        var delegate = mock(BroadcasterCache.class);
        when(delegate.retrieveFromCache("b1", "uuid-1")).thenReturn(delegateMessages);
        return new AiCoalescingBroadcasterCache(delegate);
    }

    private AiStreamMessage parse(Object message) throws JsonProcessingException {
        var raw = (RawMessage) message;
        return AiStreamMessage.parse((String) raw.message());
    }

    @Test
    public void testCoalescesMultipleTokensIntoOne() throws Exception {
        var messages = new ArrayList<Object>(List.of(
                streamingTextMsg("s1", "Hello ", 1),
                streamingTextMsg("s1", "world", 2),
                streamingTextMsg("s1", "!", 3),
                streamingTextMsg("s1", " How", 4),
                streamingTextMsg("s1", " are you", 5),
                completeMsg("s1", 6)
        ));

        var cache = createCache(messages);
        var result = cache.retrieveFromCache("b1", "uuid-1");

        // Should be 2 messages: 1 coalesced streaming text + 1 complete
        assertEquals(2, result.size());

        var coalesced = parse(result.get(0));
        assertTrue(coalesced.isStreamingText());
        assertEquals("Hello world! How are you", coalesced.data());

        var complete = parse(result.get(1));
        assertTrue(complete.isComplete());
    }

    @Test
    public void testPreservesNonAiMessages() {
        var nonAi = new RawMessage("plain text message");
        var messages = new ArrayList<Object>(List.of(
                nonAi,
                streamingTextMsg("s1", "Hello", 1),
                completeMsg("s1", 2)
        ));

        var cache = createCache(messages);
        var result = cache.retrieveFromCache("b1", "uuid-1");

        assertEquals(3, result.size());
        assertSame(nonAi, result.get(0));
    }

    @Test
    public void testCoalescedTokenConcatenatesData() throws Exception {
        var messages = new ArrayList<Object>(List.of(
                streamingTextMsg("s1", "A", 1),
                streamingTextMsg("s1", "B", 2),
                streamingTextMsg("s1", "C", 3),
                completeMsg("s1", 4)
        ));

        var cache = createCache(messages);
        var result = cache.retrieveFromCache("b1", "uuid-1");

        var coalesced = parse(result.get(0));
        assertEquals("ABC", coalesced.data());
    }

    @Test
    public void testSeqNumbersAreMonotonic() throws Exception {
        var messages = new ArrayList<Object>(List.of(
                streamingTextMsg("s1", "Hello ", 1),
                streamingTextMsg("s1", "world", 2),
                completeMsg("s1", 3)
        ));

        var cache = createCache(messages);
        var result = cache.retrieveFromCache("b1", "uuid-1");

        assertEquals(2, result.size());

        var first = parse(result.get(0));
        var second = parse(result.get(1));

        assertTrue(first.seq() < second.seq(), "seq values should be monotonically increasing");
        assertEquals(1L, first.seq());
        assertEquals(2L, second.seq());
    }

    @Test
    public void testEmptyTokenListPassesThrough() {
        var messages = new ArrayList<Object>(List.of(
                completeMsg("s1", 1)
        ));

        var cache = createCache(messages);
        var result = cache.retrieveFromCache("b1", "uuid-1");

        // Only the complete message, no synthetic streaming text
        assertEquals(1, result.size());
    }

    @Test
    public void testMultipleSessionsCoalescedIndependently() throws Exception {
        var messages = new ArrayList<Object>(List.of(
                streamingTextMsg("s1", "Hello ", 1),
                streamingTextMsg("s2", "Bonjour ", 1),
                streamingTextMsg("s1", "world", 2),
                streamingTextMsg("s2", "monde", 2),
                completeMsg("s1", 3),
                completeMsg("s2", 3)
        ));

        var cache = createCache(messages);
        var result = cache.retrieveFromCache("b1", "uuid-1");

        // Should have 4 messages: coalesced-s1, complete-s1, coalesced-s2, complete-s2
        assertEquals(4, result.size());

        var coalescedS1 = parse(result.get(0));
        assertTrue(coalescedS1.isStreamingText());
        assertEquals("s1", coalescedS1.sessionId());
        assertEquals("Hello world", coalescedS1.data());

        var completeS1 = parse(result.get(1));
        assertTrue(completeS1.isComplete());
        assertEquals("s1", completeS1.sessionId());

        var coalescedS2 = parse(result.get(2));
        assertTrue(coalescedS2.isStreamingText());
        assertEquals("s2", coalescedS2.sessionId());
        assertEquals("Bonjour monde", coalescedS2.data());

        var completeS2 = parse(result.get(3));
        assertTrue(completeS2.isComplete());
        assertEquals("s2", completeS2.sessionId());
    }

    @Test
    public void testEmptyListPassesThrough() {
        var cache = createCache(new ArrayList<>());
        var result = cache.retrieveFromCache("b1", "uuid-1");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testNullListPassesThrough() {
        var delegate = mock(BroadcasterCache.class);
        when(delegate.retrieveFromCache("b1", "uuid-1")).thenReturn(null);
        var cache = new AiCoalescingBroadcasterCache(delegate);

        assertNull(cache.retrieveFromCache("b1", "uuid-1"));
    }

    @Test
    public void testMetadataMessagePreserved() throws Exception {
        var messages = new ArrayList<Object>(List.of(
                streamingTextMsg("s1", "Hello", 1),
                metadataMsg("s1", 2),
                streamingTextMsg("s1", " world", 3),
                completeMsg("s1", 4)
        ));

        var cache = createCache(messages);
        var result = cache.retrieveFromCache("b1", "uuid-1");

        // coalesced-streaming-text("Hello"), metadata, coalesced-streaming-text(" world"), complete
        assertEquals(4, result.size());

        var chunk1 = parse(result.get(0));
        assertTrue(chunk1.isStreamingText());
        assertEquals("Hello", chunk1.data());

        var metadata = parse(result.get(1));
        assertTrue(metadata.isMetadata());

        var chunk2 = parse(result.get(2));
        assertTrue(chunk2.isStreamingText());
        assertEquals(" world", chunk2.data());

        var complete = parse(result.get(3));
        assertTrue(complete.isComplete());
    }
}
