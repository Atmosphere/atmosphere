/*
 * Copyright 2008-2020 Async-IO.org
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple {@link org.atmosphere.cpr.BroadcasterCache} that use an {@link javax.servlet.http.HttpSession} to cache
 * messages.
 *
 * @author Jeanfrancois Arcand
 *
 */
public class SessionBroadcasterCache extends AbstractBroadcasterCache {

    private static final Logger logger = LoggerFactory.getLogger(SessionBroadcasterCache.class);
    private static final String ERROR_MESSAGE = "Session was null. The request has been recycled by the underlying container";

    public SessionBroadcasterCache() {
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message) {
        long now = System.nanoTime();
        CacheMessage cacheMessage = put(message, now, uuid);

        if (uuid.equals(NULL)) return cacheMessage;

        try {
            HttpSession session = config.resourcesFactory().find(uuid).session();
            if (session == null) {
                logger.error(ERROR_MESSAGE);
                return cacheMessage;
            }

            session.setAttribute(broadcasterId, String.valueOf(now));
        } catch (IllegalStateException ex) {
            logger.trace("", ex);
            logger.warn("The Session has been invalidated. Message will be lost.");
        }
        return cacheMessage;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, String uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("AtmosphereResource can't be null");
        }

        List<Object> result = new ArrayList<>();
        try {
            AtmosphereResource r = config.resourcesFactory().find(uuid);

            if (r == null) {
                logger.trace("Invalid UUID {}", uuid);
                return result;
            }

            HttpSession session = r.session();
            if (session == null) {
                logger.error(ERROR_MESSAGE);
                return result;
            }

            String cacheHeaderTimeStr = (String)session.getAttribute(broadcasterId);
            if (cacheHeaderTimeStr == null) return result;
            long cacheHeaderTime = Long.parseLong(cacheHeaderTimeStr);

            return get(cacheHeaderTime);
        } catch (IllegalStateException ex) {
            logger.trace("", ex);
            logger.warn("The Session has been invalidated. Unable to retrieve cached messages");
            return Collections.emptyList();
        }
    }
}
