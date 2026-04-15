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

import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryResponseCacheTest {

    private InMemoryResponseCache cache;

    @BeforeEach
    void setUp() {
        cache = new InMemoryResponseCache(10);
    }

    @Test
    void putAndGetReturnsEntry() {
        var response = new CachedResponse("hello", null, Instant.now(), Duration.ofMinutes(5));
        cache.put("key1", response);
        var result = cache.get("key1");
        assertTrue(result.isPresent());
        assertEquals("hello", result.get().text());
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        assertTrue(cache.get("nonexistent").isEmpty());
    }

    @Test
    void expiredEntryIsRemovedOnGet() {
        var expired = new CachedResponse("old", null, Instant.now().minus(Duration.ofHours(1)), Duration.ofMinutes(1));
        cache.put("expired", expired);
        assertEquals(1, cache.size());
        assertTrue(cache.get("expired").isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void lruEvictionOnOverflow() {
        var cache3 = new InMemoryResponseCache(3);
        for (int i = 0; i < 5; i++) {
            cache3.put("k" + i, new CachedResponse("v" + i, null, Instant.now(), Duration.ofMinutes(5)));
        }
        assertEquals(3, cache3.size());
        // Oldest entries (k0, k1) should be evicted
        assertTrue(cache3.get("k0").isEmpty());
        assertTrue(cache3.get("k1").isEmpty());
        assertTrue(cache3.get("k2").isPresent());
    }

    @Test
    void invalidateRemovesEntry() {
        cache.put("a", new CachedResponse("v", null, Instant.now(), Duration.ofMinutes(5)));
        assertEquals(1, cache.size());
        cache.invalidate("a");
        assertEquals(0, cache.size());
    }

    @Test
    void clearRemovesAll() {
        cache.put("a", new CachedResponse("1", null, Instant.now(), Duration.ofMinutes(5)));
        cache.put("b", new CachedResponse("2", null, Instant.now(), Duration.ofMinutes(5)));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void purgeExpiredReturnsCount() {
        var fresh = new CachedResponse("fresh", null, Instant.now(), Duration.ofMinutes(10));
        var expired = new CachedResponse("old", null, Instant.now().minus(Duration.ofHours(1)), Duration.ofMinutes(1));
        cache.put("fresh", fresh);
        cache.put("expired", expired);
        assertEquals(2, cache.size());
        int removed = cache.purgeExpired();
        assertEquals(1, removed);
        assertEquals(1, cache.size());
    }

    @Test
    void defaultConstructorUses1024Max() {
        var defaultCache = new InMemoryResponseCache();
        for (int i = 0; i < 1025; i++) {
            defaultCache.put("k" + i, new CachedResponse("v", null, Instant.now(), Duration.ofMinutes(5)));
        }
        assertEquals(1024, defaultCache.size());
    }

    @Test
    void zeroMaxEntriesThrows() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryResponseCache(0));
    }

    @Test
    void negativeMaxEntriesThrows() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryResponseCache(-1));
    }

    @Test
    void singleEntryCacheWorks() {
        var cache1 = new InMemoryResponseCache(1);
        cache1.put("a", new CachedResponse("1", null, Instant.now(), Duration.ofMinutes(5)));
        cache1.put("b", new CachedResponse("2", null, Instant.now(), Duration.ofMinutes(5)));
        assertEquals(1, cache1.size());
        assertTrue(cache1.get("a").isEmpty());
        assertTrue(cache1.get("b").isPresent());
    }

    @Test
    void tokenUsagePreserved() {
        var usage = TokenUsage.of(10, 20);
        var response = new CachedResponse("text", usage, Instant.now(), Duration.ofMinutes(5));
        cache.put("key", response);
        var result = cache.get("key");
        assertTrue(result.isPresent());
        assertEquals(usage, result.get().usage());
    }
}
