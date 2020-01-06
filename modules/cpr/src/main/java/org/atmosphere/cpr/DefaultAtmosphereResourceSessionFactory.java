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
package org.atmosphere.cpr;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author uklance (https://github.com/uklance)
 */
public class DefaultAtmosphereResourceSessionFactory implements AtmosphereResourceSessionFactory {
    private final ConcurrentMap<String, AtmosphereResourceSession> sessions = new ConcurrentHashMap<String, AtmosphereResourceSession>();

    private final AtmosphereResourceEventListener disconnectListener = new AtmosphereResourceEventListenerAdapter() {
        public void onDisconnect(AtmosphereResourceEvent event) {
            String uuid = event.getResource().uuid();
            AtmosphereResourceSession session = sessions.remove(uuid);
            if (session != null) {
                session.invalidate();
            }
        }

        public String toString() {
            return "DefaultAtmosphereResourceSessionFactory.disconnectListener";
        }
    };

    @Override
    public AtmosphereResourceSession getSession(AtmosphereResource r, boolean create) {
        AtmosphereResourceSession session = sessions.get(r.uuid());
        if (create && session == null) {
            r.addEventListener(getDisconnectListener());
            session = new DefaultAtmosphereResourceSession();

            AtmosphereResourceSession existing = sessions.putIfAbsent(r.uuid(), session);

            if (existing != null) {
                session = existing;
            }
        }
        return session;
    }

    @Override
    public AtmosphereResourceSession getSession(AtmosphereResource resource) {
        return getSession(resource, true);
    }

    @Override
    public void destroy() {
        sessions.clear();
    }

    /**
     * Used in testing
     */
    protected AtmosphereResourceEventListener getDisconnectListener() {
        return disconnectListener;
    }
}
