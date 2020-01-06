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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * Allows invalidating and restoring HTTP session timeout.
 *
 * @author Miro Bezjak
 * @since 0.9
 */
public final class SessionTimeoutSupport {

    private static final String KEY = "atmosphere.session.timeout.restorer";
    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutSupport.class);

    /**
     * Disable HTTP session timeout.
     */
    public static void setupTimeout(AtmosphereConfig config, HttpSession session) {
        if (session == null)
            return;

        try {
            SessionTimeoutRestorer restorer = getOrCreate(config, session);

            restorer.setup(session);
        } catch (Exception e) {
            logger.trace("", e);
        }
    }

    /**
     * Try to restore HTTP session timeout that was set before disabling it.
     */
    public static void restoreTimeout(HttpSession session) {
        if (session == null)
            return;

        try {
            SessionTimeoutRestorer restorer = get(session);

            if (restorer != null)
                restorer.restore(session);
        } catch (Exception e) {
            logger.trace("", e);
        }
    }

    public static void restoreTimeout(HttpServletRequest request) {
        restoreTimeout(request.getSession(false));
    }

    private static SessionTimeoutRestorer get(HttpSession s) {
        return (SessionTimeoutRestorer) s.getAttribute(KEY);
    }

    // NOT 100% thread-safe. The Servlet API does not provide an atomic getAndSet operation. In theory we could use
    // double-checked locking, but the Servlet spec doesn't guarantee that the session object is always the same
    // instance, so we have no lock to synchronize with reliably.
    private static SessionTimeoutRestorer getOrCreate(AtmosphereConfig config, HttpSession s) {
        SessionTimeoutRestorer restorer = (SessionTimeoutRestorer) s.getAttribute(KEY);
        if (restorer == null) {
            restorer = new SessionTimeoutRestorer(config, s.getMaxInactiveInterval());
            s.setAttribute(KEY, restorer);
        }
        return restorer;
    }

}
