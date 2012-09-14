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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.atmosphere.cpr.HeaderConfig.X_CACHE_DATE;

/**
 * {@link BroadcasterCache} implementation based on the X-Cache-Date headers sent by the client.
 *
 * @author Jeanfrancois Arcand
 */
public class HeaderBroadcasterCache extends AbstractBroadcasterCache {

    private final Logger logger = LoggerFactory.getLogger(HeaderBroadcasterCache.class);

    @Override
    public void addToCache(String broadcasterId, AtmosphereResource r, Message e) {

        String id = e.id;
        long now = System.currentTimeMillis();
        readWriteLock.writeLock().lock();
        try {
            boolean hasMessageWithSameId = messagesIds.contains(id);
            if (!hasMessageWithSameId) {
                logger.trace("Added {} to the cache", e.message);
                CacheMessage cacheMessage = new CacheMessage(id, now, e.message);
                messages.add(cacheMessage);
                messagesIds.add(id);
            }
        } finally {
            readWriteLock.writeLock().unlock();
        }

        if (r != null) {
            r.getResponse().setHeader(X_CACHE_DATE, String.valueOf(now));
        }
    }

    @Override
    public List<Object> retrieveFromCache(String id, AtmosphereResource r) {
        if (r == null) {
            throw new IllegalArgumentException("AtmosphereResource can't be null");
        }

        AtmosphereRequest request = r.getRequest();
        String cacheHeader = request.getHeader(X_CACHE_DATE);
        r.getResponse().setHeader(X_CACHE_DATE, String.valueOf(System.currentTimeMillis()));
        if (cacheHeader == null || cacheHeader.isEmpty()) {
            return Collections.emptyList();
        }

        long cacheHeaderTime = Long.valueOf(cacheHeader);
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
        return result;
    }
}
