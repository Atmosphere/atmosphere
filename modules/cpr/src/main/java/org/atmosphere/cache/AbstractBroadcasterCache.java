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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCache;

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

import static org.atmosphere.cpr.HeaderConfig.X_CACHE_DATE;

/**
 * Abstract {@link org.atmosphere.cpr.BroadcasterCache} which is used to implement headers or query parameters or
 * session based caching.
 *
 * @author Paul Khodchenkov
 * @author Jeanfrancois Arcand
 */
public abstract class AbstractBroadcasterCache implements BroadcasterCache {
    protected final List<CacheMessage> messages = new LinkedList<CacheMessage>();
    protected final Set<String> messagesIds = new HashSet<String>();
    protected final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    protected ScheduledFuture scheduledFuture;
    protected long maxCacheTime = TimeUnit.MINUTES.toMillis(2);//2 minutes
    protected long invalidateCacheInterval = TimeUnit.MINUTES.toMillis(1);//1 minute
    protected ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();

    public void setInvalidateCacheInterval(long invalidateCacheInterval) {
        this.invalidateCacheInterval = invalidateCacheInterval;
    }

    public void setMaxCacheTime(long maxCacheTime) {
        this.maxCacheTime = maxCacheTime;
    }

    @Override
    public void start() {
        reaper.scheduleAtFixedRate(new Runnable() {

            public void run() {
                readWriteLock.writeLock().lock();
                try {
                    long now = System.currentTimeMillis();
                    List<CacheMessage> expiredMessages = new ArrayList<CacheMessage>();

                    for (CacheMessage message : messages) {
                        if (message.getCreateTime() <= now - maxCacheTime) {
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
        }, 0, invalidateCacheInterval, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }
}
