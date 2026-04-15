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
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketMessageSuspendInterceptorTest {

    private WebSocketMessageSuspendInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketMessageSuspendInterceptor();
    }

    private AtmosphereResourceImpl createMock(boolean isWebSocketMessage) {
        AtmosphereResourceImpl r = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest rawRequest = mock(AtmosphereRequest.class);
        AtmosphereResponse response = mock(AtmosphereResponse.class);

        when(r.getResponse()).thenReturn(response);
        when(r.getRequest(false)).thenReturn(rawRequest);

        if (isWebSocketMessage) {
            when(rawRequest.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn("true");
        }
        return r;
    }

    @Test
    void alwaysReturnsContinue() {
        AtmosphereResourceImpl r = createMock(true);
        assertEquals(Action.CONTINUE, interceptor.inspect(r));
    }

    @Test
    void setsActionForWebSocketMessage() {
        AtmosphereResourceImpl r = createMock(true);
        interceptor.inspect(r);

        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(r).setAction(captor.capture());
        assertEquals(Action.TYPE.SUSPEND_MESSAGE, captor.getValue().type());
    }

    @Test
    void doesNotSetActionForNonWebSocketMessage() {
        AtmosphereResourceImpl r = createMock(false);
        Action result = interceptor.inspect(r);
        assertEquals(Action.CONTINUE, result);
        verify(r, never()).setAction(org.mockito.ArgumentMatchers.any(Action.class));
    }

    @Test
    void extendsAtmosphereInterceptorAdapter() {
        assertEquals(
                org.atmosphere.cpr.AtmosphereInterceptorAdapter.class,
                WebSocketMessageSuspendInterceptor.class.getSuperclass());
    }
}
