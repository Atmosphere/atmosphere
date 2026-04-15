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

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionCreationInterceptorTest {

    private SessionCreationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SessionCreationInterceptor();
    }

    private AtmosphereResourceImpl createMock(String uuid, String method,
                                              boolean hasSession, boolean isWebSocketMessage) {
        AtmosphereResourceImpl r = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest request = mock(AtmosphereRequest.class);
        AtmosphereRequest rawRequest = mock(AtmosphereRequest.class);
        AtmosphereResponse response = mock(AtmosphereResponse.class);

        when(r.uuid()).thenReturn(uuid);
        when(r.getRequest()).thenReturn(request);
        when(r.getResponse()).thenReturn(response);
        when(request.getMethod()).thenReturn(method);

        // Utils.webSocketMessage casts to AtmosphereResourceImpl and calls getRequest(false)
        when(r.getRequest(false)).thenReturn(rawRequest);
        if (isWebSocketMessage) {
            when(rawRequest.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn("true");
        }

        if (hasSession) {
            when(r.session(false)).thenReturn(mock(jakarta.servlet.http.HttpSession.class));
        }

        return r;
    }

    @Test
    void firstGetRequestWithNoSessionReturnsCancelled() {
        AtmosphereResourceImpl r = createMock("uuid-1", "GET", false, false);
        Action result = interceptor.inspect(r);
        assertEquals(Action.CANCELLED, result);
        verify(r).session(true);
    }

    @Test
    void secondGetRequestReturnsContinue() {
        AtmosphereResourceImpl r1 = createMock("uuid-2", "GET", false, false);
        interceptor.inspect(r1);

        AtmosphereResourceImpl r2 = createMock("uuid-2", "GET", false, false);
        Action result = interceptor.inspect(r2);
        assertEquals(Action.CONTINUE, result);
    }

    @Test
    void webSocketMessageReturnsContinue() {
        AtmosphereResourceImpl r = createMock("uuid-3", "GET", false, true);
        Action result = interceptor.inspect(r);
        assertEquals(Action.CONTINUE, result);
    }

    @Test
    void nonGetRequestReturnsContinue() {
        AtmosphereResourceImpl r = createMock("uuid-4", "POST", false, false);
        Action result = interceptor.inspect(r);
        assertEquals(Action.CONTINUE, result);
    }

    @Test
    void existingSessionReturnsContinue() {
        AtmosphereResourceImpl r = createMock("uuid-5", "GET", true, false);
        Action result = interceptor.inspect(r);
        assertEquals(Action.CONTINUE, result);
    }

    @Test
    void thirdGetAfterRemovalReturnsCancelledAgain() {
        AtmosphereResourceImpl r1 = createMock("uuid-6", "GET", false, false);
        assertEquals(Action.CANCELLED, interceptor.inspect(r1));

        // Second request removes uuid from ids
        AtmosphereResourceImpl r2 = createMock("uuid-6", "GET", false, false);
        assertEquals(Action.CONTINUE, interceptor.inspect(r2));

        // Third request — uuid no longer in ids, no session → CANCELLED again
        AtmosphereResourceImpl r3 = createMock("uuid-6", "GET", false, false);
        assertEquals(Action.CANCELLED, interceptor.inspect(r3));
    }

    @Test
    void extendsAtmosphereInterceptorAdapter() {
        assertEquals(
                org.atmosphere.cpr.AtmosphereInterceptorAdapter.class,
                SessionCreationInterceptor.class.getSuperclass());
    }
}
