/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.cpr.BroadcasterCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
 * Abstract {@link org.atmosphere.cpr.BroadcasterCache} which is used to implement headers or query parameters or
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
    protected long maxCacheTime = TimeUnit.MINUTES.toMillis(2);//2 minutes
    protected long invalidateCacheInterval = TimeUnit.MINUTES.toMillis(1);//1 minute
    protected ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();
    protected boolean isShared = false;
    protected final List<BroadcasterCacheInspector> inspectors = new LinkedList<BroadcasterCacheInspector>();

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
    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }

        if (!isShared) {
            reaper.shutdown();
        }
    }

    protected void put(Message message, Long now) {
        if (!inspect(message)) return;

        logger.trace("Caching message {} for Broadcaster {}", message.message);

        readWriteLock.writeLock().lock();
        try {
            boolean hasMessageWithSameId = messagesIds.contains(message.id);
            if (!hasMessageWithSameId) {
                CacheMessage cacheMessage = new CacheMessage(message.id, now, message.message);
                messages.add(cacheMessage);
                messagesIds.add(message.id);
            }
        } finally {
            readWriteLock.writeLock().unlock();
        }
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
     * Set to true the associated {@link #getReaper()} is shared amongs {@link BroadcasterCache}
     *
     * @param isShared to true if shared. False by default.
     * @return this
     */
    public AbstractBroadcasterCache setShared(boolean isShared) {
        this.isShared = isShared;
        return this;
    }

    /**
     * Set the {@link ScheduledExecutorService} to clear the cached message.
     *
     * @param reaper the {@link ScheduledExecutorService} to clear the cached message.
     * @return this
     */
    public AbstractBroadcasterCache setReaper(ScheduledExecutorService reaper) {
        this.reaper = reaper;
        return this;
    }

    /**
     * Return the {@link ScheduledExecutorService}
     *
     * @return the {@link ScheduledExecutorService}
     */
    public ScheduledExecutorService getReaper() {
        return reaper;
    }

    /**
     * Set the time, in millisecond, the cache will be checked and purged.
     *
     * @param invalidateCacheInterval
     * @return this
     */
    public AbstractBroadcasterCache setInvalidateCacheInterval(long invalidateCacheInterval) {
        this.invalidateCacheInterval = invalidateCacheInterval;
        return this;
    }

    /**
     * Set the maxium time, in millisecond, a message stay alive in the cache.
     *
     * @param maxCacheTime the maxium time, in millisecond, a message stay alive in the cache.
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

    protected boolean inspect(Message m) {
        for (BroadcasterCacheInspector b : inspectors) {
              if (!b.inspect(m)) return false;
        }
        return true;
    }
}
