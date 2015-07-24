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
package org.atmosphere.cpr;

import org.atmosphere.cache.CacheMessage;

/**
 * Listener for {@link org.atmosphere.cpr.BroadcasterCache}
 */
public interface BroadcasterCacheListener {

    /**
     * Invoked when a message is added to the cache
     * @param broadcasterId
     * @param cacheMessage
     */
    void onAddCache(String broadcasterId, CacheMessage cacheMessage);

    /**
     * Invoked when a message is removed from the cache.
     * @param broadcasterId
     * @param cacheMessage
     */
    void onRemoveCache(String broadcasterId, CacheMessage cacheMessage);

}
