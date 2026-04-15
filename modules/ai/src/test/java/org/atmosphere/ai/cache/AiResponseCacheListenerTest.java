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

import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class AiResponseCacheListenerTest {

    private static CacheMessage streamingTextMsg(String sessionId) {
        var json = "{\"sessionId\":\"" + sessionId + "\",\"type\":\"streaming-text\",\"text\":\"hello\"}";
        return new CacheMessage("id-1", new RawMessage(json), "uuid-1");
    }

    private static CacheMessage completeMsg(String sessionId) {
        var json = "{\"sessionId\":\"" + sessionId + "\",\"type\":\"complete\"}";
        return new CacheMessage("id-2", new RawMessage(json), "uuid-1");
    }

    private static CacheMessage errorMsg(String sessionId) {
        var json = "{\"sessionId\":\"" + sessionId + "\",\"type\":\"error\",\"message\":\"fail\"}";
        return new CacheMessage("id-3", new RawMessage(json), "uuid-1");
    }

    @Test
    void streamingTextIncrementsCount() {
        var listener = new AiResponseCacheListener();
        listener.onAddCache("b1", streamingTextMsg("s1"));
        assertEquals(1, listener.getCachedStreamingTextCount("s1"));

        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", streamingTextMsg("s1"));
        assertEquals(3, listener.getCachedStreamingTextCount("s1"));
    }

    @Test
    void completeFiresCoalescedListener() {
        var listener = new AiResponseCacheListener();
        List<CoalescedCacheEvent> events = new ArrayList<>();
        listener.addCoalescedListener(events::add);

        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", completeMsg("s1"));

        assertEquals(1, events.size());
        assertEquals("s1", events.getFirst().sessionId());
        assertEquals("b1", events.getFirst().broadcasterId());
        assertEquals(2, events.getFirst().totalStreamingTexts());
        assertEquals("complete", events.getFirst().status());
    }

    @Test
    void errorFiresCoalescedListener() {
        var listener = new AiResponseCacheListener();
        List<CoalescedCacheEvent> events = new ArrayList<>();
        listener.addCoalescedListener(events::add);

        listener.onAddCache("b1", streamingTextMsg("s2"));
        listener.onAddCache("b1", errorMsg("s2"));

        assertEquals(1, events.size());
        assertEquals("error", events.getFirst().status());
        assertEquals(1, events.getFirst().totalStreamingTexts());
    }

    @Test
    void noListenerDoesNotThrow() {
        var listener = new AiResponseCacheListener();
        assertDoesNotThrow(() -> {
            listener.onAddCache("b1", streamingTextMsg("s1"));
            listener.onAddCache("b1", completeMsg("s1"));
        });
    }

    @Test
    void multipleSessionsTrackedIndependently() {
        var listener = new AiResponseCacheListener();
        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", streamingTextMsg("s2"));

        assertEquals(2, listener.getCachedStreamingTextCount("s1"));
        assertEquals(1, listener.getCachedStreamingTextCount("s2"));
    }

    @Test
    void completeClearsCountForSession() {
        var listener = new AiResponseCacheListener();
        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", completeMsg("s1"));

        assertEquals(0, listener.getCachedStreamingTextCount("s1"));
    }

    @Test
    void onRemoveCacheIsNoOp() {
        var listener = new AiResponseCacheListener();
        assertDoesNotThrow(() ->
                listener.onRemoveCache("b1", streamingTextMsg("s1")));
    }

    @Test
    void nonRawMessageIsIgnored() {
        var listener = new AiResponseCacheListener();
        var msg = new CacheMessage("id-1", "plain string", "uuid-1");
        assertDoesNotThrow(() -> listener.onAddCache("b1", msg));
        assertEquals(0, listener.getCachedStreamingTextCount("s1"));
    }

    @Test
    void nonStringInnerMessageIsIgnored() {
        var listener = new AiResponseCacheListener();
        var msg = new CacheMessage("id-1", new RawMessage(42), "uuid-1");
        assertDoesNotThrow(() -> listener.onAddCache("b1", msg));
        assertEquals(0, listener.getCachedStreamingTextCount("s1"));
    }

    @Test
    void removeCoalescedListenerStopsNotification() {
        var listener = new AiResponseCacheListener();
        List<CoalescedCacheEvent> events = new ArrayList<>();
        CoalescedCacheEventListener coalescedListener = events::add;

        listener.addCoalescedListener(coalescedListener);
        listener.removeCoalescedListener(coalescedListener);

        listener.onAddCache("b1", streamingTextMsg("s1"));
        listener.onAddCache("b1", completeMsg("s1"));

        assertEquals(0, events.size());
    }

    @Test
    void extractJsonFieldFindsValue() {
        var json = "{\"sessionId\":\"abc\",\"type\":\"streaming-text\"}";
        assertEquals("abc", AiResponseCacheListener.extractJsonField(json, "sessionId"));
        assertEquals("streaming-text", AiResponseCacheListener.extractJsonField(json, "type"));
    }

    @Test
    void extractJsonFieldReturnsNullForMissing() {
        var json = "{\"sessionId\":\"abc\"}";
        var result = AiResponseCacheListener.extractJsonField(json, "missing");
        assertEquals(null, result);
    }

    @Test
    void completeWithNoStreamingTextsReportsZeroCount() {
        var listener = new AiResponseCacheListener();
        List<CoalescedCacheEvent> events = new ArrayList<>();
        listener.addCoalescedListener(events::add);

        listener.onAddCache("b1", completeMsg("s1"));

        assertEquals(1, events.size());
        assertEquals(0, events.getFirst().totalStreamingTexts());
    }
}
