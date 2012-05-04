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
package org.atmosphere.cpr;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * Allows invalidating and restoring HTTP session timeout.
 *
 * @since 0.9
 * @author Miro Bezjak
 */
public final class SessionTimeoutSupport {

    private static final String KEY = "atmosphere.session.timeout.restorer";

    /**
     * Disable HTTP session timeout.
     */
    public static void setupTimeout(HttpSession session) {
        if (session == null) return;

        bind(session, createRestorer(session));

        session.setMaxInactiveInterval(-1);
    }

    /**
     * Try to restore HTTP session timeout that was set before disabling it.
     */
    public static void restoreTimeout(HttpSession session) {
        if (session == null) return;

        SessionTimeoutRestorer restorer = unbind(session);

        if (restorer != null) {
            restorer.restore(session);
        }
    }

    public static void restoreTimeout(HttpServletRequest request) {
        restoreTimeout(request.getSession(false));
    }

    private static SessionTimeoutRestorer createRestorer(HttpSession session) {
        return new SessionTimeoutRestorer(session.getMaxInactiveInterval());
    }

    private static void bind(HttpSession s, SessionTimeoutRestorer r) {
        s.setAttribute(KEY, r);
    }

    private static SessionTimeoutRestorer unbind(HttpSession s) {
        if (s == null) return null;

        SessionTimeoutRestorer r = (SessionTimeoutRestorer) s.getAttribute(KEY);
        s.removeAttribute(KEY);
        return r;
    }

}
