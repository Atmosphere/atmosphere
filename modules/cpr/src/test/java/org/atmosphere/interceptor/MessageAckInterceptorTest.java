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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageAckInterceptorTest {

    private MessageAckInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new MessageAckInterceptor();
        interceptor.configure(mock(AtmosphereConfig.class));
    }

    @Test
    void assignsMessageIdToInitialRequest() {
        var resource = mockResource(false);
        when(resource.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID)).thenReturn(null);
        when(resource.getRequest().getParameter(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID)).thenReturn(null);

        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        // Verify a message ID was set as request attribute
        verify(resource.getRequest()).setAttribute(
                org.mockito.ArgumentMatchers.eq(FrameworkConfig.MESSAGE_ID),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void usesClientProvidedMessageIdFromHeader() {
        var resource = mockResource(false);
        when(resource.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID))
                .thenReturn("client-msg-123");

        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        verify(resource.getRequest()).setAttribute(FrameworkConfig.MESSAGE_ID, "client-msg-123");
    }

    @Test
    void usesClientProvidedMessageIdFromQueryParam() {
        var resource = mockResource(false);
        when(resource.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID)).thenReturn(null);
        when(resource.getRequest().getParameter(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID))
                .thenReturn("query-msg-456");

        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        verify(resource.getRequest()).setAttribute(FrameworkConfig.MESSAGE_ID, "query-msg-456");
    }

    @Test
    void acksWebSocketMessageWithId() {
        var resource = mockResource(true);
        when(resource.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID))
                .thenReturn("ws-msg-789");

        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        verify(resource.getResponse()).setHeader(HeaderConfig.X_ATMOSPHERE_ACK, "ws-msg-789");
        assertEquals(1, interceptor.totalAcks());
    }

    @Test
    void skipsAckForWebSocketMessageWithoutId() {
        var resource = mockResource(true);
        when(resource.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID)).thenReturn(null);
        when(resource.getRequest().getParameter(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID)).thenReturn(null);

        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        assertEquals(0, interceptor.totalAcks());
    }

    @Test
    void priorityIsBeforeDefault() {
        assertEquals(InvokationOrder.BEFORE_DEFAULT, interceptor.priority());
    }

    @Test
    void toStringIncludesAckCount() {
        assertTrue(interceptor.toString().contains("acks=0"));
    }

    private AtmosphereResourceImpl mockResource(boolean webSocketMessage) {
        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);

        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest(false)).thenReturn(request);

        // Utils.webSocketMessage checks FrameworkConfig.WEBSOCKET_MESSAGE attribute
        if (webSocketMessage) {
            when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE))
                    .thenReturn(Boolean.TRUE);
        }

        return resource;
    }
}
