/*
 * Copyright 2015 Async-IO.org
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Abstract {@link org.atmosphere.cpr.BroadcasterCache} which is used to implement headers, query parameters or
 * session based caching.
 *
 * @author Paul Khodchenkov
 * @author Jeanfrancois Arcand
 */
public abstract class AbstractBroadcasterCache implements BroadcasterCache {
    private final Logger logger = LoggerFactory.getLogger(AbstractBroadcasterCache.class);

    protected final List<CacheMessage> messages = new LinkedList<CacheMessage>();
    protected final Set<String> messagesIds = new HashSet<String>();
    protected final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    protected ScheduledFuture scheduledFuture;
    protected long maxCacheTime = TimeUnit.MINUTES.toMillis(2); // 2 minutes
    protected long invalidateCacheInterval = TimeUnit.MINUTES.toMillis(1); // 1 minute
    protected ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();
    protected boolean isShared;
    protected final List<BroadcasterCacheInspector> inspectors = new LinkedList<BroadcasterCacheInspector>();
    protected final List<Object> emptyList = Collections.<Object>emptyList();
    protected final List<BroadcasterCacheListener> listeners = new LinkedList<BroadcasterCacheListener>();
    protected AtmosphereConfig config;

    @Override
    public void start() {
        scheduledFuture = reaper.scheduleAtFixedRate(new Runnable() {

            public void run() {
                readWriteLock.writeLock().lock();
                try {
                    long now = System.nanoTime();
                    List<CacheMessage> expiredMessages = new ArrayList<CacheMessage>();

                    for (CacheMessage message : messages) {
                        if (TimeUnit.NANOSECONDS.toMillis(now - message.getCreateTime()) > maxCacheTime) {
                            expiredMessages.add(message);
                        }
                    }

                    for (CacheMessage expiredMessage : expiredMessages) {
                        messages.remove(expiredMessage);
                        messagesIds.remove(expiredMessage.getId());
                    }
                } finally {
                    readWriteLock.writeLock().unlock();
                }
            }
        }, 0, invalidateCacheInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cleanup() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    @Override
    public void stop() {
        cleanup();
        if (!isShared) {
            reaper.shutdown();
        }
    }

    protected CacheMessage put(BroadcastMessage message, Long now, String uuid) {
        if (!inspect(message)) return null;

        logger.trace("Caching message {} for Broadcaster {}", message.message());

        readWriteLock.writeLock().lock();
        CacheMessage cacheMessage = null;
        try {
            boolean hasMessageWithSameId = messagesIds.contains(message.id());
            if (!hasMessageWithSameId) {
                cacheMessage = new CacheMessage(message.id(), now, message.message(), uuid);
                messages.add(cacheMessage);
                messagesIds.add(message.id());
            }
        } finally {
            readWriteLock.writeLock().unlock();
        }
        return cacheMessage;
    }

    protected List<Object> get(long cacheHeaderTime) {
        List<Object> result = new ArrayList<Object>();
        readWriteLock.readLock().lock();
        try {
            for (CacheMessage cacheMessage : messages) {
                if (cacheMessage.getCreateTime() > cacheHeaderTime) {
                    result.add(cacheMessage.getMessage());
                }
            }

        } finally {
            readWriteLock.readLock().unlock();
        }

        logger.trace("Retrieved messages {}", result);
        return result;
    }

    /**
     * Set the delay between cache purges.
     *
     * @param invalidateCacheInterval the purge interval in milliseconds
     * @return this
     */
    public AbstractBroadcasterCache setInvalidateCacheInterval(long invalidateCacheInterval) {
        this.invalidateCacheInterval = invalidateCacheInterval;
        return this;
    }

    /**
     * Set the maximum time a message stays alive in the cache.
     *
     * @param maxCacheTime the maximum time in milliseconds.
     * @return this
     */
    public AbstractBroadcasterCache setMaxCacheTime(long maxCacheTime) {
        this.maxCacheTime = maxCacheTime;
        return this;
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector b) {
        inspectors.add(b);
        return this;
    }

    protected boolean inspect(BroadcastMessage m) {
        for (BroadcasterCacheInspector b : inspectors) {
            if (!b.inspect(m)) return false;
        }
        return true;
    }

    @Override
    public void configure(AtmosphereConfig config) {
        Object o = config.properties().get("shared");
        if (o != null) {
            isShared = Boolean.parseBoolean(o.toString());
        }

        if (isShared) {
            reaper = ExecutorsFactory.getScheduler(config);
        } else {
            reaper = Executors.newSingleThreadScheduledExecutor();
        }
        this.config = config;
    }

    @Override
    public BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage cache) {
        if (cache != null) {
            messages.remove(cache);
            messagesIds.remove(cache.getId());
        }
        return this;
    }

    @Override
    public BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r) {
        logger.warn("Not supported");
        return this;
    }


    @Override
    public BroadcasterCache cacheCandidate(String broadcasterId, String uuid) {
        logger.warn("Not supported");
        return this;
    }

    @Override
    public BroadcasterCache addBroadcasterCacheListener(BroadcasterCacheListener l) {
        logger.warn("Not supported");
        return this;
    }

    @Override
    public BroadcasterCache removeBroadcasterCacheListener(BroadcasterCacheListener l) {
        logger.warn("Not supported");
        return this;
    }
}
