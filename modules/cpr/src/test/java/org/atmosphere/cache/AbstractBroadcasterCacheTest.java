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
package org.atmosphere.cache;

import org.atmosphere.cpr.BroadcasterCacheListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractBroadcasterCacheTest {

    private TestBroadcasterCache cache;

    /**
     * Minimal concrete subclass to test the template methods.
     */
    static class TestBroadcasterCache extends AbstractBroadcasterCache {
        @Override
        public CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message) {
            return put(message, System.currentTimeMillis(), uuid);
        }

        @Override
        public List<Object> retrieveFromCache(String broadcasterId, String uuid) {
            return get(0);
        }
    }

    static class NoOpListener implements BroadcasterCacheListener {
        @Override
        public void onAddCache(String broadcasterId, CacheMessage cacheMessage) {
        }

        @Override
        public void onRemoveCache(String broadcasterId, CacheMessage cacheMessage) {
        }
    }

    @BeforeEach
    void setUp() {
        cache = new TestBroadcasterCache();
    }

    @Test
    void putAndGetMessages() {
        cache.addToCache("b1", "u1", new BroadcastMessage("hello"));
        var result = cache.retrieveFromCache("b1", "u1");
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("hello", result.getFirst());
    }

    @Test
    void duplicateMessageIdsAreRejected() {
        var msg1 = new BroadcastMessage("1", "first");
        var msg2 = new BroadcastMessage("1", "second");

        cache.addToCache("b1", "u1", msg1);
        cache.addToCache("b1", "u1", msg2);

        var result = cache.retrieveFromCache("b1", "u1");
        assertEquals(1, result.size());
    }

    @Test
    void getFiltersMessagesByTime() {
        long now = System.currentTimeMillis();
        cache.put(new BroadcastMessage("old"), now - 10000, "u1");
        cache.put(new BroadcastMessage("new"), now + 10000, "u1");

        var result = cache.get(now);
        assertEquals(1, result.size());
        assertEquals("new", result.getFirst());
    }

    @Test
    void clearCacheRemovesSpecificMessage() {
        cache.addToCache("b1", "u1", new BroadcastMessage("hello"));
        var messages = cache.messages;
        assertNotNull(messages);
        assertEquals(1, messages.size());

        cache.clearCache("b1", "u1", messages.getFirst());
        assertEquals(0, messages.size());
    }

    @Test
    void setMaxCacheTimeIsFluent() {
        var result = cache.setMaxCacheTime(5000);
        assertSame(cache, result);
    }

    @Test
    void setInvalidateCacheIntervalIsFluent() {
        var result = cache.setInvalidateCacheInterval(3000);
        assertSame(cache, result);
    }

    @Test
    void inspectorReturnsSelf() {
        var result = cache.inspector(m -> true);
        assertSame(cache, result);
    }

    @Test
    void inspectorFiltersMessages() {
        cache.inspector(m -> !m.message().equals("blocked"));

        cache.addToCache("b1", "u1", new BroadcastMessage("allowed"));
        cache.addToCache("b1", "u1", new BroadcastMessage("blocked"));

        var result = cache.retrieveFromCache("b1", "u1");
        assertEquals(1, result.size());
        assertEquals("allowed", result.getFirst());
    }

    @Test
    void excludeFromCacheReturnsSelf() {
        var result = cache.excludeFromCache("b1", null);
        assertSame(cache, result);
    }

    @Test
    void cacheCandidateReturnsSelf() {
        var result = cache.cacheCandidate("b1", "u1");
        assertSame(cache, result);
    }

    @Test
    void addAndRemoveListenerReturnsSelf() {
        var listener = new NoOpListener();
        assertSame(cache, cache.addBroadcasterCacheListener(listener));
        assertSame(cache, cache.removeBroadcasterCacheListener(listener));
    }

    @Test
    void emptyGetReturnsEmptyList() {
        var result = cache.retrieveFromCache("b1", "u1");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void startAndStopDoNotThrow() {
        cache.start();
        cache.stop();
    }

    @Test
    void cleanupCancelsScheduledTask() {
        cache.start();
        cache.cleanup();
        // After cleanup, scheduledFuture should be cancelled
        assertTrue(cache.scheduledFuture == null || cache.scheduledFuture.isCancelled());
    }
}
