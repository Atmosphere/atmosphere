/*
 * Copyright 2017 Async-IO.org
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

import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.BroadcasterCache;
import org.atmosphere.runtime.BroadcasterCacheListener;
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
    public CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message) {
        logger.trace("Message {} might be lost! Please install a proper BroadcasterCache", message.message());
        return null;
    }

    @Override
    public List<Object> retrieveFromCache(String id, String uuid) {
        return Collections.<Object>emptyList();
    }

    @Override
    public BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage cache) {
        return this;
    }

    @Override
    public BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r) {
        return this;
    }

    @Override
    public BroadcasterCache cacheCandidate(String broadcasterId, String uuid) {
        return this;
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector interceptor) {
        return this;
    }

    @Override
    public BroadcasterCache addBroadcasterCacheListener(BroadcasterCacheListener l) {
        return null;
    }

    @Override
    public BroadcasterCache removeBroadcasterCacheListener(BroadcasterCacheListener l) {
        return null;
    }

    @Override
    public void configure(AtmosphereConfig config) {
    }
}
