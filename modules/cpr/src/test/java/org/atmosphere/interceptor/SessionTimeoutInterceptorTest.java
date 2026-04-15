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
package org.atmosphere.interceptor;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.SessionTimeoutRestorer;
import org.atmosphere.cpr.SessionTimeoutSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTimeoutInterceptorTest {

    @Test
    void setupTimeoutWithNullSessionDoesNotThrow() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        assertDoesNotThrow(() -> SessionTimeoutSupport.setupTimeout(config, null));
    }

    @Test
    void restoreTimeoutWithNullSessionDoesNotThrow() {
        assertDoesNotThrow(() -> SessionTimeoutSupport.restoreTimeout((HttpSession) null));
    }

    @Test
    void setupTimeoutSetsSessionAttribute() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        HttpSession session = mock(HttpSession.class);

        when(session.getMaxInactiveInterval()).thenReturn(1800);
        when(session.getAttribute("atmosphere.session.timeout.restorer")).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL)).thenReturn(null);

        SessionTimeoutSupport.setupTimeout(config, session);

        verify(session).setAttribute(
                org.mockito.ArgumentMatchers.eq("atmosphere.session.timeout.restorer"),
                org.mockito.ArgumentMatchers.any(SessionTimeoutRestorer.class));
    }

    @Test
    void setupTimeoutSetsInternalInterval() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        HttpSession session = mock(HttpSession.class);

        when(session.getMaxInactiveInterval()).thenReturn(1800);
        when(session.getAttribute("atmosphere.session.timeout.restorer")).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL)).thenReturn("300");

        SessionTimeoutSupport.setupTimeout(config, session);

        verify(session).setMaxInactiveInterval(300);
    }

    @Test
    void restorerSetupAndRestoreSymmetric() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL)).thenReturn("-1");

        HttpSession session = mock(HttpSession.class);
        when(session.getMaxInactiveInterval()).thenReturn(1800);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 1800);
        restorer.setup(session);
        verify(session).setMaxInactiveInterval(-1);

        restorer.restore(session);
        verify(session).setMaxInactiveInterval(1800);
    }

    @Test
    void restorerMultipleSetupsThenRestores() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL)).thenReturn("-1");

        HttpSession session = mock(HttpSession.class);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 600);

        restorer.setup(session);
        verify(session).setMaxInactiveInterval(-1);

        restorer.setup(session);

        restorer.restore(session);

        restorer.restore(session);
        verify(session).setMaxInactiveInterval(600);
    }

    @Test
    void restorerToStringContainsTimeout() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL)).thenReturn(null);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 3600);
        String str = restorer.toString();
        assertNotNull(str);
        org.junit.jupiter.api.Assertions.assertTrue(str.contains("3600"));
        org.junit.jupiter.api.Assertions.assertTrue(str.contains("requestCount=0"));
    }

    @Test
    void sessionWillPassivateResetsRequestCount() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL)).thenReturn("-1");

        HttpSession session = mock(HttpSession.class);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 900);
        restorer.setup(session);

        HttpSessionEvent event = mock(HttpSessionEvent.class);
        when(event.getSession()).thenReturn(session);

        restorer.sessionWillPassivate(event);

        verify(session).setMaxInactiveInterval(900);
    }

    @Test
    void sessionDidActivateDoesNotThrow() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.SESSION_MAX_INACTIVE_INTERVAL)).thenReturn(null);

        SessionTimeoutRestorer restorer = new SessionTimeoutRestorer(config, 1800);
        HttpSessionEvent event = mock(HttpSessionEvent.class);

        assertDoesNotThrow(() -> restorer.sessionDidActivate(event));
    }

    @Test
    void restoreTimeoutFromRequest() {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(null);
        assertDoesNotThrow(() -> SessionTimeoutSupport.restoreTimeout(request));
    }
}
