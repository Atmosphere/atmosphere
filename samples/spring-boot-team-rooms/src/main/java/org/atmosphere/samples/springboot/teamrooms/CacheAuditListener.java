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
package org.atmosphere.samples.springboot.teamrooms;

import java.util.concurrent.atomic.AtomicLong;

import org.atmosphere.config.service.BroadcasterCacheListenerService;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cpr.BroadcasterCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observability for the replay path.
 *
 * <p>{@code @BroadcasterCacheListenerService} installs this on the cache. Without it the
 * cache is a black box: you can see messages arrive and see clients reconnect, but nothing
 * tells you whether the backlog is being retained or silently evicted. The counters below
 * are surfaced by {@link PresenceController} so the behaviour is observable from a browser
 * rather than only from a log.</p>
 */
@BroadcasterCacheListenerService
public class CacheAuditListener implements BroadcasterCacheListener {

    private static final Logger logger = LoggerFactory.getLogger(CacheAuditListener.class);

    private static final AtomicLong ADDED = new AtomicLong();
    private static final AtomicLong REMOVED = new AtomicLong();

    public static long added() {
        return ADDED.get();
    }

    public static long removed() {
        return REMOVED.get();
    }

    @Override
    public void onAddCache(String broadcasterId, CacheMessage cacheMessage) {
        ADDED.incrementAndGet();
        logger.trace("cached for {}: {}", broadcasterId, cacheMessage.getId());
    }

    @Override
    public void onRemoveCache(String broadcasterId, CacheMessage cacheMessage) {
        REMOVED.incrementAndGet();
        logger.trace("evicted from {}: {}", broadcasterId, cacheMessage.getId());
    }
}
