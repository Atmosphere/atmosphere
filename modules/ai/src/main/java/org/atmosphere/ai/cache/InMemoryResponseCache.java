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

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LRU in-memory {@link ResponseCache} with a bounded entry count. Entries
 * are evicted when the map exceeds {@link #maxEntries}; expired entries
 * are lazily removed on {@link #get(String)}.
 *
 * <p>Thread-safe via a single synchronized block around the
 * {@link LinkedHashMap} — sufficient for virtual-thread workloads where
 * contention is low and critical sections are microseconds.</p>
 *
 * <p><b>Eviction and TTL interaction.</b> {@code removeEldestEntry} is a
 * strict LRU policy: when the map exceeds its bound, the least-recently-used
 * entry is evicted regardless of whether it is still live. An expired entry
 * that has not yet been touched by a {@code get()} call therefore occupies
 * a slot that could have held a fresh response. This is benign churn, not a
 * memory leak — the map is still bounded at {@code maxEntries} and expired
 * entries are purged lazily on first access. Callers who want to reclaim
 * expired slots eagerly (for example under a periodic background sweeper)
 * should call {@link #purgeExpired()} on a schedule; the default behavior
 * is lazy for zero-overhead steady state.</p>
 */
public class InMemoryResponseCache implements ResponseCache {

    private final int maxEntries;
    private final Map<String, CachedResponse> map;

    public InMemoryResponseCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.map = Collections.synchronizedMap(new LinkedHashMap<String, CachedResponse>(
                16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
                return size() > InMemoryResponseCache.this.maxEntries;
            }
        });
    }

    /** Default bound: 1024 entries. */
    public InMemoryResponseCache() {
        this(1024);
    }

    @Override
    public Optional<CachedResponse> get(String key) {
        synchronized (map) {
            var entry = map.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.isExpired(Instant.now())) {
                map.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry);
        }
    }

    @Override
    public void put(String key, CachedResponse response) {
        synchronized (map) {
            map.put(key, response);
        }
    }

    @Override
    public void invalidate(String key) {
        synchronized (map) {
            map.remove(key);
        }
    }

    @Override
    public int size() {
        synchronized (map) {
            return map.size();
        }
    }

    @Override
    public void clear() {
        synchronized (map) {
            map.clear();
        }
    }

    /**
     * Walk the map once and remove every expired entry. Use for eager
     * reclamation when callers schedule a background sweep; the default
     * lazy-on-get path is sufficient for most workloads.
     *
     * @return the number of entries removed
     */
    public int purgeExpired() {
        var now = Instant.now();
        synchronized (map) {
            int before = map.size();
            map.entrySet().removeIf(e -> e.getValue().isExpired(now));
            return before - map.size();
        }
    }
}
