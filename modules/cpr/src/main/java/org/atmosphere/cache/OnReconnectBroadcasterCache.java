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
import org.atmosphere.cpr.HeaderConfig;

import java.util.Collections;
import java.util.List;

/**
 * Same as the {@link HeaderBroadcasterCache}, but will not send anything if the user connect for the first time.
 *
 * @author Jeanfrancois Arcand
 */
public class OnReconnectBroadcasterCache implements BroadcasterCache {

    private final HeaderBroadcasterCache cache = new HeaderBroadcasterCache();

    @Override
    public void start() {
        cache.start();
    }

    @Override
    public void stop() {
        cache.stop();
    }

    @Override
    public void addToCache(String id, AtmosphereResource r, Object e) {
        cache.addToCache(id, r, e);
    }

    @Override
    public List<Object> retrieveFromCache(String id, AtmosphereResource r) {
        // In this case we don't retrieve the cache
        String uuid = r.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        if (uuid == null ||  uuid.equals("0") ) {
            return Collections.emptyList();
        }
        return cache.retrieveFromCache(id,r);
    }
}