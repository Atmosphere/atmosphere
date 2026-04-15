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

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoOpsRequestTest {

    @Test
    void defaultMethodIsGet() {
        var req = new NoOpsRequest();
        assertEquals("GET", req.getMethod());
    }

    @Test
    void schemeIsWs() {
        var req = new NoOpsRequest();
        assertEquals("ws", req.getScheme());
    }

    @Test
    void contentTypeIsTextPlain() {
        var req = new NoOpsRequest();
        assertEquals("text/plain", req.getContentType());
    }

    @Test
    void requestUriIsSlash() {
        var req = new NoOpsRequest();
        assertEquals("/", req.getRequestURI());
    }

    @Test
    void emptyPathAndQueryDefaults() {
        var req = new NoOpsRequest();
        assertEquals("", req.getPathInfo());
        assertEquals("", req.getQueryString());
        assertEquals("", req.getServletPath());
        assertEquals("", req.getRemoteUser());
    }

    @Test
    void sessionCreateReturnsSession() {
        var req = new NoOpsRequest();
        assertNull(req.getSession(false));
        var session = req.getSession(true);
        assertNotNull(session);
    }

    @Test
    void sessionInvalidateAndRecreate() {
        var req = new NoOpsRequest();
        var s1 = req.getSession(true);
        assertNotNull(s1);
        s1.invalidate();
        assertNull(req.getSession(false));
        var s2 = req.getSession(true);
        assertNotNull(s2);
    }

    @Test
    void isUserInRoleReturnsFalseByDefault() {
        var req = new NoOpsRequest();
        assertFalse(req.isUserInRole("admin"));
    }

    @Test
    void isUserInRoleThrowsWhenFlagSet() {
        var req = new NoOpsRequest(true);
        assertThrows(UnsupportedOperationException.class, () -> req.isUserInRole("admin"));
    }

    @Test
    void loginDoesNothingByDefault() {
        var req = new NoOpsRequest();
        assertDoesNotThrow(() -> req.login("user", "pass"));
    }

    @Test
    void loginThrowsWhenFlagSet() {
        var req = new NoOpsRequest(true);
        assertThrows(ServletException.class, () -> req.login("user", "pass"));
    }

    @Test
    void logoutThrowsWhenFlagSet() {
        var req = new NoOpsRequest(true);
        assertThrows(ServletException.class, req::logout);
    }

    @Test
    void booleanDefaultsAreFalse() {
        var req = new NoOpsRequest();
        assertFalse(req.isRequestedSessionIdFromCookie());
        assertFalse(req.isRequestedSessionIdFromURL());
        assertFalse(req.isRequestedSessionIdValid());
        assertFalse(req.isSecure());
    }

    @Test
    void nullDefaults() {
        var req = new NoOpsRequest();
        assertNull(req.getUserPrincipal());
        assertNull(req.getAttribute("anything"));
    }

    @Test
    void intHeaderReturnsZero() {
        var req = new NoOpsRequest();
        assertEquals(0, req.getIntHeader("X-Custom"));
    }

    @Test
    void changeSessionIdReturnsSessionId() {
        var req = new NoOpsRequest();
        req.getSession(true);
        assertNotNull(req.changeSessionId());
    }
}
