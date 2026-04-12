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
}
