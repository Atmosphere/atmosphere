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
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.UUIDProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_CLIENT_IDLETIME;
import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_IDLE_CACHE_INTERVAL;
import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_MAX_PER_CLIENT;
import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_MAX_TOTAL;
import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_MESSAGE_TTL;

/**
 * An improved {@link BroadcasterCache} implementation that is based on the unique identifier (UUID) that all
 * {@link AtmosphereResource}s have.
 *
 * @author Paul Khodchenkov
 * @author Jeanfrancois Arcand
 */
public class UUIDBroadcasterCache implements BroadcasterCache {

    private final static Logger logger = LoggerFactory.getLogger(UUIDBroadcasterCache.class);

    private final Map<String, ConcurrentLinkedQueue<CacheMessage>> messages = new ConcurrentHashMap<>();
    private final Map<String, Long> activeClients = new ConcurrentHashMap<>();
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    protected final List<BroadcasterCacheInspector> inspectors = new LinkedList<>();
    private ScheduledFuture<?> scheduledFuture;
    protected ScheduledExecutorService taskScheduler;
    private long clientIdleTime = TimeUnit.SECONDS.toMillis(60); // 1 minutes
    private long invalidateCacheInterval = TimeUnit.SECONDS.toMillis(30); // 30 seconds
    private int maxPerClient = 1000;
    private long messageTTL = TimeUnit.SECONDS.toMillis(300); // 5 minutes
    private int maxTotal = 100_000;
    private boolean shared = true;
    protected final List<Object> emptyList = List.of();
    protected final List<BroadcasterCacheListener> listeners = new LinkedList<>();
    private UUIDProvider uuidProvider;
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    public UUIDBroadcasterCache() {
    }

    @Override
    public void configure(AtmosphereConfig config) {
        Object o = config.properties().get("shared");
        if (o != null) {
            shared = Boolean.parseBoolean(o.toString());
        }

        if (shared) {
            taskScheduler = ExecutorsFactory.getScheduler(config);
        } else {
            taskScheduler = Executors.newSingleThreadScheduledExecutor();
        }

        clientIdleTime = TimeUnit.SECONDS.toMillis(
                Long.parseLong(config.getInitParameter(UUIDBROADCASTERCACHE_CLIENT_IDLETIME, "60")));

        invalidateCacheInterval = TimeUnit.SECONDS.toMillis(
                Long.parseLong(config.getInitParameter(UUIDBROADCASTERCACHE_IDLE_CACHE_INTERVAL, "30")));

        maxPerClient = Integer.parseInt(config.getInitParameter(UUIDBROADCASTERCACHE_MAX_PER_CLIENT, "1000"));

        messageTTL = TimeUnit.SECONDS.toMillis(
                Long.parseLong(config.getInitParameter(UUIDBROADCASTERCACHE_MESSAGE_TTL, "300")));

        maxTotal = Integer.parseInt(config.getInitParameter(UUIDBROADCASTERCACHE_MAX_TOTAL, "100000"));

        uuidProvider = config.uuidProvider();
    }

    @Override
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(this::invalidateExpiredEntries, 0, invalidateCacheInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        cleanup();

        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Override
    public void cleanup() {
        messages.clear();
        activeClients.clear();
        inspectors.clear();

        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message) {
        if (logger.isTraceEnabled()) {
            logger.trace("Adding for AtmosphereResource {} cached messages {}", uuid, message.message());
            logger.trace("Active clients {}", activeClients());
        }

        String messageId = uuidProvider.generateUuid();
        boolean cache = inspect(message);

        CacheMessage cacheMessage = new CacheMessage(messageId, message.message(), uuid);
        if (cache) {
            if (uuid.equals(NULL)) {
                //no clients are connected right now, caching message for all active clients
                for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
                    addMessageIfNotExists(broadcasterId, entry.getKey(), cacheMessage);
                }
            } else {
                cacheCandidate(broadcasterId, uuid);
                addMessageIfNotExists(broadcasterId, uuid, cacheMessage);
            }
        }
        return cacheMessage;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, String uuid) {
        try {
            readWriteLock.writeLock().lock();
            cacheCandidate(broadcasterId, uuid);

            ConcurrentLinkedQueue<CacheMessage> clientQueue = messages.remove(uuid);
            if (clientQueue != null) {
                cacheHits.incrementAndGet();
                if (logger.isTraceEnabled()) {
                    logger.trace("Retrieved for AtmosphereResource {} cached messages {}", uuid, (long) clientQueue.size());
                    logger.trace("Available cached message {}", messages);
                }
                return clientQueue.parallelStream().map(CacheMessage::getMessage).toList();
            } else {
                cacheMisses.incrementAndGet();
                return List.of();
            }
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    @Override
    public BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage message) {
        ConcurrentLinkedQueue<CacheMessage> clientQueue = messages.get(uuid);
        if (clientQueue != null && !clientQueue.isEmpty()) {
            logger.trace("Removing for AtmosphereResource {} cached message {}", uuid, message.getMessage());
            notifyRemoveCache(broadcasterId, new CacheMessage(message.getId(), message.getCreateTime(), message.getMessage(), uuid));
            clientQueue.remove(message);
        }
        return this;
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector b) {
        inspectors.add(b);
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

    protected String uuid(AtmosphereResource r) {
        return r.uuid();
    }

    private void addMessageIfNotExists(String broadcasterId, String clientId, CacheMessage message) {
        if (!hasMessage(clientId, message.getId())) {
            addMessage(broadcasterId, clientId, message);
        } else {
            logger.debug("Duplicate message {} for client {}", message, clientId);
        }
    }

    private void addMessage(String broadcasterId, String clientId, CacheMessage message) {
        try {
            readWriteLock.readLock().lock();
            ConcurrentLinkedQueue<CacheMessage> clientQueue = messages.get(clientId);

            if (clientQueue == null) {
                clientQueue = new ConcurrentLinkedQueue<>();
                // Make sure the client is not in the process of being invalidated
                if (activeClients.get(clientId) != null) {
                    messages.put(clientId, clientQueue);
                } else {
                    // The entry has been invalidated
                    logger.debug("Client {} is no longer active. Not caching message {}}", clientId, message);
                    return;
                }
            }
            notifyAddCache(broadcasterId, message);
            clientQueue.offer(message);

            // Enforce max-per-client by evicting oldest messages
            while (clientQueue.size() > maxPerClient) {
                CacheMessage evicted = clientQueue.poll();
                if (evicted != null) {
                    evictions.incrementAndGet();
                    logger.trace("Evicted oldest message for client {} (max-per-client={})", clientId, maxPerClient);
                }
            }
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private void notifyAddCache(String broadcasterId, CacheMessage message) {
        for (BroadcasterCacheListener l : listeners) {
            try {
                l.onAddCache(broadcasterId, message);
            } catch (Exception ex) {
                logger.warn("Listener exception", ex);
            }
        }
    }

    private void notifyRemoveCache(String broadcasterId, CacheMessage message) {
        for (BroadcasterCacheListener l : listeners) {
            try {
                l.onRemoveCache(broadcasterId, message);
            } catch (Exception ex) {
                logger.warn("Listener exception", ex);
            }
        }
    }

    private boolean hasMessage(String clientId, String messageId) {
        ConcurrentLinkedQueue<CacheMessage> clientQueue = messages.get(clientId);
        return clientQueue != null && clientQueue.parallelStream().anyMatch(m -> Objects.equals(m.getId(), messageId));
    }

    public Map<String, ConcurrentLinkedQueue<CacheMessage>> messages() {
        return messages;
    }

    public Map<String, Long> activeClients() {
        return activeClients;
    }

    protected boolean inspect(BroadcastMessage m) {
        for (BroadcasterCacheInspector b : inspectors) {
            if (!b.inspect(m)) return false;
        }
        return true;
    }

    public void setInvalidateCacheInterval(long invalidateCacheInterval) {
        this.invalidateCacheInterval = invalidateCacheInterval;
        scheduledFuture.cancel(true);
        start();
    }

    public void setClientIdleTime(long clientIdleTime) {
        this.clientIdleTime = clientIdleTime;
    }

    public void setMaxPerClient(int maxPerClient) {
        this.maxPerClient = maxPerClient;
    }

    public void setMessageTTL(long messageTTLMillis) {
        this.messageTTL = messageTTLMillis;
    }

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    protected void invalidateExpiredEntries() {
        long now = System.currentTimeMillis();

        // Remove inactive clients
        Set<String> inactiveClients = new HashSet<>();
        for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
            if (now - entry.getValue() > clientIdleTime) {
                logger.trace("Invalidate client {}", entry.getKey());
                inactiveClients.add(entry.getKey());
            }
        }

        for (String clientId : inactiveClients) {
            activeClients.remove(clientId);
            messages.remove(clientId);
        }

        for (String msg : messages().keySet()) {
            if (!activeClients().containsKey(msg)) {
                messages().remove(msg);
            }
        }

        // Per-message TTL eviction (CacheMessage uses System.nanoTime())
        long nowNanos = System.nanoTime();
        long ttlNanos = TimeUnit.MILLISECONDS.toNanos(messageTTL);
        for (ConcurrentLinkedQueue<CacheMessage> queue : messages.values()) {
            queue.removeIf(m -> {
                if ((nowNanos - m.getCreateTime()) > ttlNanos) {
                    evictions.incrementAndGet();
                    return true;
                }
                return false;
            });
        }

        // Global total cap eviction — evict oldest messages across all clients
        int total = messages.values().stream().mapToInt(ConcurrentLinkedQueue::size).sum();
        while (total > maxTotal) {
            // Find the client with the oldest head message
            String oldestClient = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, ConcurrentLinkedQueue<CacheMessage>> entry : messages.entrySet()) {
                CacheMessage head = entry.getValue().peek();
                if (head != null && head.getCreateTime() < oldestTime) {
                    oldestTime = head.getCreateTime();
                    oldestClient = entry.getKey();
                }
            }
            if (oldestClient != null) {
                ConcurrentLinkedQueue<CacheMessage> queue = messages.get(oldestClient);
                if (queue != null) {
                    queue.poll();
                    evictions.incrementAndGet();
                    total--;
                    if (queue.isEmpty()) {
                        messages.remove(oldestClient);
                    }
                }
            } else {
                break;
            }
        }
    }

    @Override
    public BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r) {
        activeClients.remove(r.uuid());
        return this;
    }

    @Override
    public BroadcasterCache cacheCandidate(String broadcasterId, String uuid) {
        activeClients.put(uuid, System.currentTimeMillis());
        return this;
    }

    @Override
    public String toString() {
        return this.getClass().getName();
    }

    public List<BroadcasterCacheListener> listeners() {
        return listeners;
    }

    public List<BroadcasterCacheInspector> inspectors() {
        return inspectors;
    }

    /**
     * @return the total number of cached messages across all clients
     */
    public int totalSize() {
        return messages.values().stream().mapToInt(ConcurrentLinkedQueue::size).sum();
    }

    /**
     * @return the total number of evictions (max-per-client, TTL, global cap)
     */
    public long evictionCount() {
        return evictions.get();
    }

    /**
     * @return the total number of cache hits (retrieveFromCache found messages)
     */
    public long hitCount() {
        return cacheHits.get();
    }

    /**
     * @return the total number of cache misses (retrieveFromCache found no messages)
     */
    public long missCount() {
        return cacheMisses.get();
    }
}
