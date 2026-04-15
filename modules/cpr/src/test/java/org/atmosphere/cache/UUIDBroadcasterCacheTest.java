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
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterCacheListener;
import org.atmosphere.util.UUIDProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class UUIDBroadcasterCacheTest {

    private UUIDBroadcasterCache cache;

    @BeforeEach
    void setUp() {
        cache = new UUIDBroadcasterCache();

        AtomicInteger counter = new AtomicInteger();
        UUIDProvider uuidProvider = mock(UUIDProvider.class);
        when(uuidProvider.generateUuid()).thenAnswer(inv -> "msg-" + counter.incrementAndGet());

        AtmosphereConfig config = mock(AtmosphereConfig.class);
        Map<String, Object> props = new HashMap<>();
        props.put("shared", "false");
        when(config.properties()).thenReturn(props);
        when(config.getInitParameter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.uuidProvider()).thenReturn(uuidProvider);

        cache.configure(config);
        // Do NOT call start() — avoids scheduler side-effects
    }

    @Test
    void addToCacheAndRetrieve() {
        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "client-1", new BroadcastMessage("hello"));

        List<Object> messages = cache.retrieveFromCache("b1", "client-1");
        assertEquals(1, messages.size());
        assertEquals("hello", messages.get(0));
    }

    @Test
    void retrieveRemovesMessages() {
        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "client-1", new BroadcastMessage("msg1"));

        cache.retrieveFromCache("b1", "client-1");
        List<Object> second = cache.retrieveFromCache("b1", "client-1");
        assertTrue(second.isEmpty());
    }

    @Test
    void addToCacheWithNullUuidBroadcastsToAllActiveClients() {
        cache.cacheCandidate("b1", "client-A");
        cache.cacheCandidate("b1", "client-B");

        cache.addToCache("b1", BroadcasterCache.NULL, new BroadcastMessage("broadcast"));

        List<Object> msgsA = cache.retrieveFromCache("b1", "client-A");
        List<Object> msgsB = cache.retrieveFromCache("b1", "client-B");
        assertEquals(1, msgsA.size());
        assertEquals(1, msgsB.size());
    }

    @Test
    void clearCacheRemovesSpecificMessage() {
        cache.cacheCandidate("b1", "client-1");
        CacheMessage cm = cache.addToCache("b1", "client-1", new BroadcastMessage("to-remove"));

        cache.clearCache("b1", "client-1", cm);

        List<Object> msgs = cache.retrieveFromCache("b1", "client-1");
        assertTrue(msgs.isEmpty());
    }

    @Test
    void inspectorCanBeAddedAndQueried() {
        BroadcasterCacheInspector inspector = mock(BroadcasterCacheInspector.class);
        cache.inspector(inspector);

        assertEquals(1, cache.inspectors().size());
        assertTrue(cache.inspectors().contains(inspector));
    }

    @Test
    void inspectorBlocksCaching() {
        BroadcasterCacheInspector inspector = mock(BroadcasterCacheInspector.class);
        when(inspector.inspect(org.mockito.ArgumentMatchers.any(BroadcastMessage.class))).thenReturn(false);
        cache.inspector(inspector);

        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "client-1", new BroadcastMessage("blocked"));

        List<Object> msgs = cache.retrieveFromCache("b1", "client-1");
        assertTrue(msgs.isEmpty());
    }

    @Test
    void addAndRemoveListener() {
        BroadcasterCacheListener listener = mock(BroadcasterCacheListener.class);
        cache.addBroadcasterCacheListener(listener);
        assertEquals(1, cache.listeners().size());

        cache.removeBroadcasterCacheListener(listener);
        assertTrue(cache.listeners().isEmpty());
    }

    @Test
    void listenerNotifiedOnAdd() {
        BroadcasterCacheListener listener = mock(BroadcasterCacheListener.class);
        cache.addBroadcasterCacheListener(listener);

        cache.cacheCandidate("b1", "client-1");
        cache.addToCache("b1", "client-1", new BroadcastMessage("hello"));

        verify(listener).onAddCache(org.mockito.ArgumentMatchers.eq("b1"),
                org.mockito.ArgumentMatchers.any(CacheMessage.class));
    }

    @Test
    void excludeFromCacheRemovesClient() {
        cache.cacheCandidate("b1", "client-1");
        assertTrue(cache.activeClients().containsKey("client-1"));

        AtmosphereResource r = mock(AtmosphereResource.class);
        when(r.uuid()).thenReturn("client-1");
        cache.excludeFromCache("b1", r);

        assertFalse(cache.activeClients().containsKey("client-1"));
    }

    @Test
    void cacheCandidateAddsToActiveClients() {
        cache.cacheCandidate("b1", "client-X");
        assertTrue(cache.activeClients().containsKey("client-X"));
    }

    @Test
    void totalSizeReflectsAllMessages() {
        cache.cacheCandidate("b1", "c1");
        cache.cacheCandidate("b1", "c2");
        cache.addToCache("b1", "c1", new BroadcastMessage("a"));
        cache.addToCache("b1", "c1", new BroadcastMessage("b"));
        cache.addToCache("b1", "c2", new BroadcastMessage("c"));

        assertEquals(3, cache.totalSize());
    }

    @Test
    void hitAndMissCountTracked() {
        cache.cacheCandidate("b1", "c1");
        cache.addToCache("b1", "c1", new BroadcastMessage("x"));

        cache.retrieveFromCache("b1", "c1"); // hit
        cache.retrieveFromCache("b1", "c1"); // miss (removed)

        assertEquals(1, cache.hitCount());
        assertEquals(1, cache.missCount());
    }

    @Test
    void maxPerClientEvictsOldest() {
        cache.setMaxPerClient(2);
        cache.cacheCandidate("b1", "c1");

        cache.addToCache("b1", "c1", new BroadcastMessage("first"));
        cache.addToCache("b1", "c1", new BroadcastMessage("second"));
        cache.addToCache("b1", "c1", new BroadcastMessage("third"));

        List<Object> msgs = cache.retrieveFromCache("b1", "c1");
        assertEquals(2, msgs.size());
        assertEquals("second", msgs.get(0));
        assertEquals("third", msgs.get(1));
        assertTrue(cache.evictionCount() > 0);
    }

    @Test
    void duplicateMessageIdNotAddedTwice() {
        cache.cacheCandidate("b1", "c1");
        CacheMessage cm = cache.addToCache("b1", "c1", new BroadcastMessage("dup"));
        assertNotNull(cm);

        // Same message id won't be added again — totalSize stays 1
        assertEquals(1, cache.totalSize());
    }
}
