/*
 * Copyright 2013 Jeanfrancois Arcand
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

import java.util.Collections;
import java.util.List;

import static org.atmosphere.cpr.HeaderConfig.X_CACHE_DATE;

/**
 * {@link BroadcasterCache} implementation based on the X-Cache-Date headers sent by the client.
 *
 * @deprecated Use UUIDBroadcasterCache.
 * @author Jeanfrancois Arcand
 */
public class HeaderBroadcasterCache extends AbstractBroadcasterCache {

    private final Logger logger = LoggerFactory.getLogger(HeaderBroadcasterCache.class);

    @Override
    public CacheMessage addToCache(String broadcasterId, AtmosphereResource r, BroadcastMessage e) {

        long now = System.nanoTime();
        CacheMessage cacheMessage = put(e, now);

        if (r != null) {
            r.getResponse().setHeader(X_CACHE_DATE, String.valueOf(now));
        }
        return cacheMessage;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, AtmosphereResource r) {
        if (r == null) {
            throw new IllegalArgumentException("AtmosphereResource can't be null");
        }

        AtmosphereRequest request = r.getRequest();
        String cacheHeader = request.getHeader(X_CACHE_DATE);
        r.getResponse().setHeader(X_CACHE_DATE, String.valueOf(System.nanoTime()));
        if (cacheHeader == null || cacheHeader.isEmpty()) {
            return Collections.emptyList();
        }

        long cacheHeaderTime = 0;
        if (!cacheHeader.isEmpty()) {
            cacheHeaderTime = Long.valueOf(cacheHeader);
        }
        return get(cacheHeaderTime);
    }

}
