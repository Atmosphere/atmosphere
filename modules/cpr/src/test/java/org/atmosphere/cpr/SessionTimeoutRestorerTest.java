/*
 * Copyright 2008-2026 Async-IO.org
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;

import org.junit.jupiter.api.Test;

class SessionTimeoutRestorerTest {

    private AtmosphereConfig createConfig(String sessionMaxInactive) {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL))
                .thenReturn(sessionMaxInactive);
        return config;
    }

    @Test
    void setupSetsInternalTimeoutOnFirstCall() {
        AtmosphereConfig config = createConfig("300");
        HttpSession session = mock(HttpSession.class);
        when(session.getMaxInactiveInterval()).thenReturn(1800);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 1800);
        restorer.setup(session);

        verify(session).setMaxInactiveInterval(300);
    }

    @Test
    void setupDoesNotRefreshTimeoutOnSubsequentCalls() {
        AtmosphereConfig config = createConfig("300");
        HttpSession session = mock(HttpSession.class);
        when(session.getMaxInactiveInterval()).thenReturn(1800);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 1800);
        restorer.setup(session);
        restorer.setup(session);

        // Only the first call triggers setMaxInactiveInterval
        verify(session).setMaxInactiveInterval(300);
    }

    @Test
    void restoreResetsOriginalTimeoutWhenCountReachesZero() {
        AtmosphereConfig config = createConfig("300");
        HttpSession session = mock(HttpSession.class);
        when(session.getMaxInactiveInterval()).thenReturn(1800);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 1800);
        restorer.setup(session);
        restorer.restore(session);

        verify(session).setMaxInactiveInterval(1800);
    }

    @Test
    void restoreDoesNotResetTimeoutWhenRequestsStillActive() {
        AtmosphereConfig config = createConfig("300");
        HttpSession session = mock(HttpSession.class);
        when(session.getMaxInactiveInterval()).thenReturn(1800);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 1800);
        restorer.setup(session);
        restorer.setup(session);

        // First restore: count goes from 2 to 1 (still active, no timeout change for this)
        restorer.restore(session);

        // Second restore: count goes to 0, restores original timeout
        restorer.restore(session);

        verify(session).setMaxInactiveInterval(1800);
    }

    @Test
    void usesNegativeOneWhenNoConfigParameter() {
        AtmosphereConfig config = createConfig(null);
        HttpSession session = mock(HttpSession.class);
        when(session.getMaxInactiveInterval()).thenReturn(600);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 600);
        restorer.setup(session);

        verify(session).setMaxInactiveInterval(-1);
    }

    @Test
    void sessionWillPassivateResetsCountAndRestoresTimeout() {
        AtmosphereConfig config = createConfig("300");
        HttpSession session = mock(HttpSession.class);
        when(session.getMaxInactiveInterval()).thenReturn(1800);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 1800);
        restorer.setup(session);
        restorer.setup(session);

        HttpSessionEvent event = mock(HttpSessionEvent.class);
        when(event.getSession()).thenReturn(session);

        restorer.sessionWillPassivate(event);

        // After passivation, request count is 0 so original timeout is restored
        verify(session).setMaxInactiveInterval(1800);
    }

    @Test
    void toStringContainsTimeoutAndRequestCount() {
        AtmosphereConfig config = createConfig(null);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 900);
        String str = restorer.toString();

        assertNotNull(str);
        assertEquals("SessionTimeoutRestorer[timeout=900, requestCount=0]", str);
    }
}
