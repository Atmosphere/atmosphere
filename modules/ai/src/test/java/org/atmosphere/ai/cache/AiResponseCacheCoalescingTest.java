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

import static org.junit.jupiter.api.Assertions.*;

public class AiResponseCacheCoalescingTest {

    @Test
    public void testFiresOnComplete() {
        var listener = new AiResponseCacheListener();
        var received = new ArrayList<CoalescedCacheEvent>();
        listener.addCoalescedListener(received::add);

        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", token("s1", 2));
        listener.onAddCache("b1", token("s1", 3));
        listener.onAddCache("b1", complete("s1", 4));

        assertEquals(1, received.size());
        var event = received.get(0);
        assertEquals("s1", event.sessionId());
        assertEquals("b1", event.broadcasterId());
        assertEquals(3, event.totalTokens());
        assertEquals("complete", event.status());
    }

    @Test
    public void testFiresOnError() {
        var listener = new AiResponseCacheListener();
        var received = new ArrayList<CoalescedCacheEvent>();
        listener.addCoalescedListener(received::add);

        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", token("s1", 2));
        listener.onAddCache("b1", error("s1", 3));

        assertEquals(1, received.size());
        var event = received.get(0);
        assertEquals("error", event.status());
        assertEquals(2, event.totalTokens());
    }

    @Test
    public void testNoOpWithoutListeners() {
        var listener = new AiResponseCacheListener();

        // Should not throw
        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", complete("s1", 2));
    }

    @Test
    public void testMultipleListeners() {
        var listener = new AiResponseCacheListener();
        var received1 = new ArrayList<CoalescedCacheEvent>();
        var received2 = new ArrayList<CoalescedCacheEvent>();
        listener.addCoalescedListener(received1::add);
        listener.addCoalescedListener(received2::add);

        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", complete("s1", 2));

        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
    }

    @Test
    public void testRemoveListener() {
        var listener = new AiResponseCacheListener();
        var received = new ArrayList<CoalescedCacheEvent>();
        CoalescedCacheEventListener l = received::add;
        listener.addCoalescedListener(l);
        listener.removeCoalescedListener(l);

        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", complete("s1", 2));

        assertTrue(received.isEmpty());
    }

    @Test
    public void testElapsedTimeNonNegative() {
        var listener = new AiResponseCacheListener();
        var received = new ArrayList<CoalescedCacheEvent>();
        listener.addCoalescedListener(received::add);

        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", complete("s1", 2));

        assertTrue(received.get(0).elapsedMs() >= 0);
    }

    @Test
    public void testIndependentSessions() {
        var listener = new AiResponseCacheListener();
        var received = new ArrayList<CoalescedCacheEvent>();
        listener.addCoalescedListener(received::add);

        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", token("s2", 1));
        listener.onAddCache("b1", token("s2", 2));
        listener.onAddCache("b1", token("s2", 3));
        listener.onAddCache("b1", complete("s1", 2));
        listener.onAddCache("b1", complete("s2", 4));

        assertEquals(2, received.size());
        var s1Event = received.stream()
                .filter(e -> "s1".equals(e.sessionId())).findFirst().orElseThrow();
        var s2Event = received.stream()
                .filter(e -> "s2".equals(e.sessionId())).findFirst().orElseThrow();
        assertEquals(1, s1Event.totalTokens());
        assertEquals(3, s2Event.totalTokens());
    }

    @Test
    public void testListenerExceptionIsolation() {
        var listener = new AiResponseCacheListener();
        var received = new ArrayList<CoalescedCacheEvent>();

        // First listener throws
        listener.addCoalescedListener(event -> {
            throw new RuntimeException("boom");
        });
        // Second listener should still fire
        listener.addCoalescedListener(received::add);

        listener.onAddCache("b1", token("s1", 1));
        listener.onAddCache("b1", complete("s1", 2));

        assertEquals(1, received.size());
    }

    private static CacheMessage token(String sessionId, int seq) {
        var json = "{\"type\":\"token\",\"data\":\"t\",\"sessionId\":\""
                + sessionId + "\",\"seq\":" + seq + "}";
        return new CacheMessage("id-" + System.nanoTime(), new RawMessage(json), "uuid-1");
    }

    private static CacheMessage complete(String sessionId, int seq) {
        var json = "{\"type\":\"complete\",\"sessionId\":\""
                + sessionId + "\",\"seq\":" + seq + "}";
        return new CacheMessage("id-" + System.nanoTime(), new RawMessage(json), "uuid-1");
    }

    private static CacheMessage error(String sessionId, int seq) {
        var json = "{\"type\":\"error\",\"data\":\"fail\",\"sessionId\":\""
                + sessionId + "\",\"seq\":" + seq + "}";
        return new CacheMessage("id-" + System.nanoTime(), new RawMessage(json), "uuid-1");
    }
}
