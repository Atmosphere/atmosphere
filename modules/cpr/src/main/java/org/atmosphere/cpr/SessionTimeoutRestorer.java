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

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionEvent;

/**
 * Capable of restoring HTTP session timeout to given value.
 *
 * @since 0.9
 * @author Miro Bezjak
 */
public final class SessionTimeoutRestorer implements Serializable, HttpSessionActivationListener {

    private static final long serialVersionUID = -126253550299206646L;

    private final int timeout;

    private final AtomicInteger requestCount = new AtomicInteger(0);

    public SessionTimeoutRestorer(int timeout) {
        this.timeout = timeout;
    }

    public void setup(HttpSession session) {
        int oldCount = requestCount.getAndIncrement();

        if (oldCount == 0)
            refreshTimeout(session);
    }

    public void restore(HttpSession session) {
        int count = requestCount.decrementAndGet();

        if (count == 0)
            refreshTimeout(session);
    }

    // Synchronization ensures timeout updates are thread-safe, and the additional check makes sure we know
    // precisely what the timeout should be.
    private synchronized void refreshTimeout(HttpSession session) {
        if (requestCount.get() > 0)
            session.setMaxInactiveInterval(-1);
        else
            session.setMaxInactiveInterval(timeout);
    }

    @Override
    public void sessionWillPassivate(HttpSessionEvent hse) {
        requestCount.set(0);
        refreshTimeout(hse.getSession());
    }

    @Override
    public void sessionDidActivate(HttpSessionEvent hse) {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SessionTimeoutRestorer[timeout=");
        sb.append(timeout);
        sb.append(", requestCount=");
        sb.append(requestCount.get());
        sb.append(']');
        return sb.toString();
    }

}
