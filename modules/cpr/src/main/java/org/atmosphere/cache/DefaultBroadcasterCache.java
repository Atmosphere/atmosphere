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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

public class DefaultBroadcasterCache implements BroadcasterCache {
    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcasterCache.class);

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void configure(BroadcasterConfig config) {
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, AtmosphereResource r, BroadcastMessage e) {
        logger.trace("Message {} will be lost! Please install a proper BroadcasterCache", e.message);
        return null;
    }

    @Override
    public List<Object> retrieveFromCache(String id, AtmosphereResource r) {
        return Collections.<Object>emptyList();
    }

    @Override
    public void clearCache(String broadcasterId, AtmosphereResource r, CacheMessage cache) {

    }

    @Override
    public void excludeFromCache(String broadcasterId, AtmosphereResource r) {
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector interceptor) {
        return this;
    }
}
