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
package org.atmosphere.websocket.protocol;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimpleHttpProtocolTest {

    private SimpleHttpProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new SimpleHttpProtocol();
    }

    @Test
    void configureWithDefaults() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.REWRITE_WEBSOCKET_REQUESTURI, "true")).thenReturn("true");

        protocol.configure(config);

        assertEquals("text/plain", protocol.contentType);
        assertEquals("POST", protocol.methodType);
        assertEquals("@@", protocol.delimiter);
    }

    @Test
    void configureWithCustomContentType() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE)).thenReturn("application/json");
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.REWRITE_WEBSOCKET_REQUESTURI, "true")).thenReturn("true");

        protocol.configure(config);

        assertEquals("application/json", protocol.contentType);
    }

    @Test
    void configureWithCustomMethodType() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD)).thenReturn("GET");
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.REWRITE_WEBSOCKET_REQUESTURI, "true")).thenReturn("true");

        protocol.configure(config);

        assertEquals("GET", protocol.methodType);
    }

    @Test
    void configureWithCustomDelimiter() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER)).thenReturn("||");
        when(config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.REWRITE_WEBSOCKET_REQUESTURI, "true")).thenReturn("true");

        protocol.configure(config);

        assertEquals("||", protocol.delimiter);
    }

    @Test
    void onMessageStringWithNullResourceReturnsNull() {
        WebSocket ws = mock(WebSocket.class);
        when(ws.resource()).thenReturn(null);

        List<AtmosphereRequest> result = protocol.onMessage(ws, "hello");

        assertNull(result);
    }

    @Test
    void onMessageBytesWithNullResourceReturnsNull() {
        WebSocket ws = mock(WebSocket.class);
        when(ws.resource()).thenReturn(null);

        List<AtmosphereRequest> result = protocol.onMessage(ws, new byte[]{1, 2, 3}, 0, 3);

        assertNull(result);
    }

    @Test
    void onMessageStringWithResourceNotInScopeReturnsEmptyList() {
        WebSocket ws = mock(WebSocket.class);
        AtmosphereResourceImpl resource = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest request = mock(AtmosphereRequest.class);

        when(ws.resource()).thenReturn(resource);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.isInScope()).thenReturn(false);

        List<AtmosphereRequest> result = protocol.onMessage(ws, "hello");

        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void onMessageStringWithSimpleMessage() {
        WebSocket ws = mock(WebSocket.class);
        AtmosphereResourceImpl resource = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest request = mock(AtmosphereRequest.class);

        when(ws.resource()).thenReturn(resource);
        when(ws.attributes()).thenReturn(new HashMap<>());
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.isInScope()).thenReturn(true);
        when(resource.session()).thenReturn(null);
        when(request.getPathInfo()).thenReturn("/test");
        when(request.getRequestURI()).thenReturn("/test");
        when(request.getContentType()).thenReturn("text/plain");
        when(request.getContextPath()).thenReturn("");
        when(request.getServletPath()).thenReturn("");
        when(request.requestURL()).thenReturn("http://localhost/test");
        when(request.headersMap()).thenReturn(Map.of());

        protocol.contentType = "text/plain";
        protocol.methodType = "POST";
        protocol.delimiter = "@@";

        List<AtmosphereRequest> result = protocol.onMessage(ws, "hello world");

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void onMessageBytesWithResourceNotInScopeReturnsEmptyList() {
        WebSocket ws = mock(WebSocket.class);
        AtmosphereResourceImpl resource = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest request = mock(AtmosphereRequest.class);

        when(ws.resource()).thenReturn(resource);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.isInScope()).thenReturn(false);

        List<AtmosphereRequest> result = protocol.onMessage(ws, new byte[]{1, 2}, 0, 2);

        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void onOpenDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        assertDoesNotThrow(() -> protocol.onOpen(ws));
    }

    @Test
    void onCloseDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        assertDoesNotThrow(() -> protocol.onClose(ws));
    }
}
