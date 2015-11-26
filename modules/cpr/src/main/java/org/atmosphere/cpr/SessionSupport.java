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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

public class SessionSupport implements HttpSessionListener {

    private final Logger logger = LoggerFactory.getLogger(SessionSupport.class);

    // Quite ugly, but gives hints about current state of Session Support.
    public static boolean initializationHint;

    public SessionSupport() {
        initializationHint = true;
    }

    @Override
    public void sessionCreated(HttpSessionEvent se) {
        logger.trace("Session created");
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        logger.trace("Session destroyed");
        try {
            HttpSession s = se.getSession();
            BroadcasterFactory f = (BroadcasterFactory) s.getAttribute(FrameworkConfig.BROADCASTER_FACTORY);
            if (f != null) {
                s.setAttribute(FrameworkConfig.BROADCASTER_FACTORY, null);
                for (Broadcaster b : f.lookupAll()) {
                    for (AtmosphereResource r : b.getAtmosphereResources()) {
                        if (r.session() != null && r.session().getId().equals(s.getId())) {
                            AtmosphereResourceImpl.class.cast(r).session(null);
                        }
                    }
                } 
            }
        } catch (Throwable t) {
            logger.warn("", t);
        }
    }
}
