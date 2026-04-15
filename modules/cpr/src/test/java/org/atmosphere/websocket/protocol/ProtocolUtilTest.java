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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtocolUtilTest {

    private WebSocket webSocket;
    private AtmosphereResourceImpl resource;
    private AtmosphereRequest request;

    @BeforeEach
    void setUp() {
        webSocket = mock(WebSocket.class);
        resource = mock(AtmosphereResourceImpl.class);
        request = mock(AtmosphereRequest.class);

        when(webSocket.resource()).thenReturn(resource);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.session()).thenReturn(null);
        when(webSocket.attributes()).thenReturn(Collections.emptyMap());
        when(request.getContextPath()).thenReturn("/ctx");
        when(request.getServletPath()).thenReturn("/servlet");
        when(request.requestURL()).thenReturn("http://localhost/ctx/servlet");
        when(request.headersMap()).thenReturn(new HashMap<>());
    }

    @Test
    void constructRequestWithNullPathInfo() {
        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, null, "/ctx/servlet/test", "GET", "application/json", true);

        AtmosphereRequest result = builder.build();
        assertNotNull(result);
        assertEquals("GET", result.getMethod());
    }

    @Test
    void constructRequestWithNonNullPathInfo() {
        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/info", "/ctx/servlet/info", "POST", "text/plain", true);

        AtmosphereRequest result = builder.build();
        assertNotNull(result);
        assertEquals("/info", result.getPathInfo());
    }

    @Test
    void constructRequestDestroyableTrue() {
        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/path", "/uri", "POST", "text/plain", true);

        AtmosphereRequest result = builder.build();
        assertNotNull(result);
        assertEquals("POST", result.getMethod());
    }

    @Test
    void constructRequestDestroyableFalse() {
        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/path", "/uri", "PUT", "text/plain", false);

        AtmosphereRequest result = builder.build();
        assertNotNull(result);
        assertEquals("PUT", result.getMethod());
    }

    @Test
    void constructRequestNullContentTypeFallsBackToRequestContentType() {
        when(request.getContentType()).thenReturn("text/html");

        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/path", "/uri", "GET", null, true);

        AtmosphereRequest result = builder.build();
        assertNotNull(result);
        assertEquals("text/html", result.getContentType());
    }

    @Test
    void constructRequestExplicitContentType() {
        when(request.getContentType()).thenReturn("text/html");

        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/path", "/uri", "GET", "application/json", true);

        AtmosphereRequest result = builder.build();
        assertNotNull(result);
        assertEquals("application/json", result.getContentType());
    }

    @Test
    void constructRequestPreservesContextAndServletPath() {
        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/path", "/ctx/servlet/path", "GET", "text/plain", true);

        AtmosphereRequest result = builder.build();
        assertEquals("/ctx", result.getContextPath());
        assertEquals("/servlet", result.getServletPath());
    }

    @Test
    void constructRequestPreservesHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom", "value");
        when(request.headersMap()).thenReturn(headers);

        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/path", "/uri", "GET", "text/plain", true);

        AtmosphereRequest result = builder.build();
        assertEquals("value", result.getHeader("X-Custom"));
    }

    @Test
    void constructRequestCopiesWebSocketAttributes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key1", "val1");
        when(webSocket.attributes()).thenReturn(attrs);

        AtmosphereRequestImpl.Builder builder = ProtocolUtil.constructRequest(
                webSocket, "/path", "/uri", "GET", "text/plain", true);

        AtmosphereRequest result = builder.build();
        assertNotNull(result);
    }
}
