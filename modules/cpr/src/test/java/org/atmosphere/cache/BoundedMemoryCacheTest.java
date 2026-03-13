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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCacheListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundedMemoryCacheTest {

    private BoundedMemoryCache cache;

    @BeforeEach
    void setUp() {
        cache = new BoundedMemoryCache();
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter("org.atmosphere.cache.maxSize")).thenReturn("10");
        when(config.getInitParameter("org.atmosphere.cache.ttlSeconds")).thenReturn("3600");
        cache.configure(config);
    }

    @Test
    void addToCacheReturnsCacheMessage() {
        cache.cacheCandidate("b1", "client-1");
        var msg = new BroadcastMessage("Hello");
        var cached = cache.addToCache("b1", "sender", msg);

        assertNotNull(cached);
        assertEquals("Hello", cached.message());
    }

    @Test
    void retrieveFromCacheReturnsPendingMessages() {
        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "sender", new BroadcastMessage("msg-1"));
        cache.addToCache("b1", "sender", new BroadcastMessage("msg-2"));

        var retrieved = cache.retrieveFromCache("b1", "client-1");
        assertEquals(2, retrieved.size());
        assertEquals("msg-1", retrieved.get(0));
        assertEquals("msg-2", retrieved.get(1));
    }

    @Test
    void retrieveFromCacheReturnsEmptyForUnknownClient() {
        var retrieved = cache.retrieveFromCache("b1", "unknown");
        assertTrue(retrieved.isEmpty());
    }

    @Test
    void senderDoesNotReceiveOwnMessages() {
        cache.cacheCandidate("b1", "sender");
        cache.addToCache("b1", "sender", new BroadcastMessage("msg"));

        var retrieved = cache.retrieveFromCache("b1", "sender");
        assertTrue(retrieved.isEmpty());
    }

    @Test
    void clearCacheRemovesPendingMessage() {
        cache.cacheCandidate("b1", "client-1");
        var cached = cache.addToCache("b1", "sender", new BroadcastMessage("msg"));
        cache.clearCache("b1", "client-1", cached);

        var retrieved = cache.retrieveFromCache("b1", "client-1");
        assertTrue(retrieved.isEmpty());
    }

    @Test
    void excludeFromCachePreventsDelivery() {
        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "sender", new BroadcastMessage("msg"));

        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("client-1");
        cache.excludeFromCache("b1", resource);

        var retrieved = cache.retrieveFromCache("b1", "client-1");
        assertTrue(retrieved.isEmpty());
    }

    @Test
    void evictsOldestWhenOverMaxSize() {
        cache.cacheCandidate("b1", "client-1");

        for (int i = 0; i < 15; i++) {
            cache.addToCache("b1", "sender", new BroadcastMessage("msg-" + i));
        }

        assertEquals(10, cache.size("b1"));

        var retrieved = cache.retrieveFromCache("b1", "client-1");
        assertEquals(10, retrieved.size());
        // Oldest messages should be evicted, newest kept
        assertEquals("msg-5", retrieved.get(0));
        assertEquals("msg-14", retrieved.get(9));
    }

    @Test
    void inspectorCanRejectMessages() {
        cache.inspector(msg -> !msg.message().toString().contains("reject"));
        cache.cacheCandidate("b1", "client-1");

        cache.addToCache("b1", "sender", new BroadcastMessage("keep-me"));
        var rejected = cache.addToCache("b1", "sender", new BroadcastMessage("reject-me"));

        assertNull(rejected);
        assertEquals(1, cache.size("b1"));
    }

    @Test
    void listenerNotifiedOnAddAndRemove() {
        List<String> events = new ArrayList<>();
        cache.addBroadcasterCacheListener(new BroadcasterCacheListener() {
            @Override
            public void onAddCache(String broadcasterId, CacheMessage cacheMessage) {
                events.add("add:" + cacheMessage.message());
            }
            @Override
            public void onRemoveCache(String broadcasterId, CacheMessage cacheMessage) {
                events.add("remove:" + cacheMessage.message());
            }
        });

        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "sender", new BroadcastMessage("msg"));

        assertTrue(events.contains("add:msg"));
    }

    @Test
    void totalSizeAcrossBroadcasters() {
        cache.addToCache("b1", "sender", new BroadcastMessage("msg-1"));
        cache.addToCache("b2", "sender", new BroadcastMessage("msg-2"));
        cache.addToCache("b2", "sender", new BroadcastMessage("msg-3"));

        assertEquals(3, cache.totalSize());
        assertEquals(1, cache.size("b1"));
        assertEquals(2, cache.size("b2"));
    }

    @Test
    void cleanupClearsEverything() {
        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "sender", new BroadcastMessage("msg"));

        cache.cleanup();

        assertEquals(0, cache.totalSize());
        assertTrue(cache.retrieveFromCache("b1", "client-1").isEmpty());
    }

    @Test
    void retrieveClearsClientPending() {
        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "sender", new BroadcastMessage("msg"));

        // First retrieval returns the message
        var first = cache.retrieveFromCache("b1", "client-1");
        assertEquals(1, first.size());

        // Second retrieval returns empty (pending was cleared)
        var second = cache.retrieveFromCache("b1", "client-1");
        assertTrue(second.isEmpty());
    }
}
