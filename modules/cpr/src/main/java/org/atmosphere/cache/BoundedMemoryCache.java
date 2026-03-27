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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bounded, in-memory {@link BroadcasterCache} with per-broadcaster size limits and TTL-based
 * eviction. Configurable via {@code org.atmosphere.cache.maxSize} (default 1000)
 * and {@code org.atmosphere.cache.ttlSeconds} (default 3600).
 *
 * @since 4.0.8
 */
public class BoundedMemoryCache implements BroadcasterCache {

    private static final Logger logger = LoggerFactory.getLogger(BoundedMemoryCache.class);

    private int maxSize = 1000;
    private long ttlNanos = TimeUnit.HOURS.toNanos(1);

    /** All cached messages for a given broadcaster, in insertion order. */
    private final ConcurrentHashMap<String, List<CacheMessage>> cache = new ConcurrentHashMap<>();

    /** Messages pending delivery per client UUID. */
    private final ConcurrentHashMap<String, Set<String>> clientPending = new ConcurrentHashMap<>();

    /** Clients excluded from receiving cached messages. */
    private final Set<String> excluded = ConcurrentHashMap.newKeySet();

    /** Inspectors invoked before caching. */
    private final List<BroadcasterCacheInspector> inspectors = new CopyOnWriteArrayList<>();

    /** Listeners notified on add/remove. */
    private final List<BroadcasterCacheListener> listeners = new CopyOnWriteArrayList<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private ScheduledExecutorService scheduler;

    @Override
    public void configure(AtmosphereConfig config) {
        var maxSizeStr = config.getInitParameter(ApplicationConfig.CACHE_MAX_SIZE);
        if (maxSizeStr != null) {
            maxSize = Integer.parseInt(maxSizeStr.trim());
        }
        var ttlStr = config.getInitParameter(ApplicationConfig.CACHE_TTL_SECONDS);
        if (ttlStr != null) {
            ttlNanos = TimeUnit.SECONDS.toNanos(Long.parseLong(ttlStr.trim()));
        }
        logger.info("BoundedMemoryCache configured: maxSize={}, ttl={}s", maxSize,
                TimeUnit.NANOSECONDS.toSeconds(ttlNanos));
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "atmosphere-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::evictExpired, 60, 60, TimeUnit.SECONDS);
        logger.debug("BoundedMemoryCache started");
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        logger.debug("BoundedMemoryCache stopped");
    }

    @Override
    public void cleanup() {
        cache.clear();
        clientPending.clear();
        excluded.clear();
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message) {
        // Run inspectors
        for (var inspector : inspectors) {
            if (!inspector.inspect(message)) {
                logger.trace("Message rejected by inspector for broadcaster {}", broadcasterId);
                return null;
            }
        }

        var cacheMsg = new CacheMessage(message.id(), message.message(), uuid);

        lock.writeLock().lock();
        try {
            var messages = cache.computeIfAbsent(broadcasterId, k -> new ArrayList<>());
            messages.add(cacheMsg);

            // Evict oldest if over max size
            while (messages.size() > maxSize) {
                var evicted = messages.removeFirst();
                notifyRemoved(broadcasterId, evicted);
            }

            // Track as pending for all active clients except the sender
            for (var entry : clientPending.entrySet()) {
                if (!entry.getKey().equals(uuid) && !excluded.contains(entry.getKey())) {
                    entry.getValue().add(cacheMsg.id());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyAdded(broadcasterId, cacheMsg);
        logger.trace("Cached message {} for broadcaster {}", cacheMsg.id(), broadcasterId);
        return cacheMsg;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, String uuid) {
        if (excluded.contains(uuid)) {
            return List.of();
        }

        var pending = clientPending.get(uuid);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }

        lock.readLock().lock();
        try {
            var messages = cache.get(broadcasterId);
            if (messages == null) {
                return List.of();
            }

            var result = new ArrayList<>();
            for (var msg : messages) {
                if (pending.contains(msg.id())) {
                    result.add(msg.message());
                }
            }
            pending.clear();

            logger.debug("Retrieved {} cached messages for {} from broadcaster {}",
                    result.size(), uuid, broadcasterId);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage cacheMessage) {
        if (cacheMessage == null) {
            return this;
        }
        var pending = clientPending.get(uuid);
        if (pending != null) {
            pending.remove(cacheMessage.id());
        }
        return this;
    }

    @Override
    public BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r) {
        excluded.add(r.uuid());
        clientPending.remove(r.uuid());
        return this;
    }

    @Override
    public BroadcasterCache cacheCandidate(String broadcasterId, String uuid) {
        clientPending.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        logger.trace("Registered cache candidate {} for broadcaster {}", uuid, broadcasterId);
        return this;
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector interceptor) {
        inspectors.add(interceptor);
        return this;
    }

    @Override
    public BroadcasterCache addBroadcasterCacheListener(BroadcasterCacheListener l) {
        listeners.add(l);
        return this;
    }

    @Override
    public BroadcasterCache removeBroadcasterCacheListener(BroadcasterCacheListener l) {
        listeners.remove(l);
        return this;
    }

    /**
     * Returns the current number of cached messages for the given broadcaster.
     */
    public int size(String broadcasterId) {
        var messages = cache.get(broadcasterId);
        return messages != null ? messages.size() : 0;
    }

    /**
     * Returns the total number of cached messages across all broadcasters.
     */
    public int totalSize() {
        return cache.values().stream().mapToInt(List::size).sum();
    }

    private void evictExpired() {
        var now = System.nanoTime();
        lock.writeLock().lock();
        try {
            for (var entry : cache.entrySet()) {
                var removed = entry.getValue().removeIf(msg -> {
                    if (now - msg.createTime() > ttlNanos) {
                        notifyRemoved(entry.getKey(), msg);
                        return true;
                    }
                    return false;
                });
                if (removed) {
                    logger.trace("Evicted expired messages from broadcaster {}", entry.getKey());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void notifyAdded(String broadcasterId, CacheMessage msg) {
        for (var l : listeners) {
            l.onAddCache(broadcasterId, msg);
        }
    }

    private void notifyRemoved(String broadcasterId, CacheMessage msg) {
        for (var l : listeners) {
            l.onRemoveCache(broadcasterId, msg);
        }
    }
}
