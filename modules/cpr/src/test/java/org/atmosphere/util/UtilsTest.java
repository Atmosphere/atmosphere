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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UtilsTest {

    @Test
    void twoConnectionsTransportTrueForStreaming() {
        assertTrue(Utils.twoConnectionsTransport(AtmosphereResource.TRANSPORT.STREAMING));
    }

    @Test
    void twoConnectionsTransportTrueForSSE() {
        assertTrue(Utils.twoConnectionsTransport(AtmosphereResource.TRANSPORT.SSE));
    }

    @Test
    void twoConnectionsTransportTrueForHtmlFile() {
        assertTrue(Utils.twoConnectionsTransport(AtmosphereResource.TRANSPORT.HTMLFILE));
    }

    @Test
    void twoConnectionsTransportTrueForLongPolling() {
        assertTrue(Utils.twoConnectionsTransport(AtmosphereResource.TRANSPORT.LONG_POLLING));
    }

    @Test
    void twoConnectionsTransportTrueForPolling() {
        assertTrue(Utils.twoConnectionsTransport(AtmosphereResource.TRANSPORT.POLLING));
    }

    @Test
    void twoConnectionsTransportFalseForWebSocket() {
        assertFalse(Utils.twoConnectionsTransport(AtmosphereResource.TRANSPORT.WEBSOCKET));
    }

    @Test
    void twoConnectionsTransportFalseForUndefined() {
        assertFalse(Utils.twoConnectionsTransport(AtmosphereResource.TRANSPORT.UNDEFINED));
    }

    @Test
    void resumableTransportTrueForLongPolling() {
        assertTrue(Utils.resumableTransport(AtmosphereResource.TRANSPORT.LONG_POLLING));
    }

    @Test
    void resumableTransportFalseForStreaming() {
        assertFalse(Utils.resumableTransport(AtmosphereResource.TRANSPORT.STREAMING));
    }

    @Test
    void resumableTransportFalseForSSE() {
        assertFalse(Utils.resumableTransport(AtmosphereResource.TRANSPORT.SSE));
    }

    @Test
    void resumableTransportFalseForWebSocket() {
        assertFalse(Utils.resumableTransport(AtmosphereResource.TRANSPORT.WEBSOCKET));
    }

    @Test
    void pollableTransportTrueForPolling() {
        assertTrue(Utils.pollableTransport(AtmosphereResource.TRANSPORT.POLLING));
    }

    @Test
    void pollableTransportTrueForClose() {
        assertTrue(Utils.pollableTransport(AtmosphereResource.TRANSPORT.CLOSE));
    }

    @Test
    void pollableTransportTrueForAjax() {
        assertTrue(Utils.pollableTransport(AtmosphereResource.TRANSPORT.AJAX));
    }

    @Test
    void pollableTransportFalseForStreaming() {
        assertFalse(Utils.pollableTransport(AtmosphereResource.TRANSPORT.STREAMING));
    }

    @Test
    void pollableTransportFalseForWebSocket() {
        assertFalse(Utils.pollableTransport(AtmosphereResource.TRANSPORT.WEBSOCKET));
    }

    @Test
    void pushMessageTrueForPolling() {
        assertTrue(Utils.pushMessage(AtmosphereResource.TRANSPORT.POLLING));
    }

    @Test
    void pushMessageTrueForUndefined() {
        assertTrue(Utils.pushMessage(AtmosphereResource.TRANSPORT.UNDEFINED));
    }

    @Test
    void pushMessageTrueForAjax() {
        assertTrue(Utils.pushMessage(AtmosphereResource.TRANSPORT.AJAX));
    }

    @Test
    void pushMessageFalseForSSE() {
        assertFalse(Utils.pushMessage(AtmosphereResource.TRANSPORT.SSE));
    }

    @Test
    void closeMessageTrueWhenHeaderIsClose() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT)).thenReturn(HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE);
        assertTrue(Utils.closeMessage(request));
    }

    @Test
    void closeMessageFalseWhenHeaderIsNull() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT)).thenReturn(null);
        assertFalse(Utils.closeMessage(request));
    }

    @Test
    void closeMessageFalseWhenHeaderIsOther() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT)).thenReturn("websocket");
        assertFalse(Utils.closeMessage(request));
    }

    @Test
    void atmosphereProtocolTrueWhenHeaderTrue() {
        Map<String, String> headers = new HashMap<>();
        headers.put(HeaderConfig.X_ATMO_PROTOCOL, "true");
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.headers(headers);
        assertTrue(Utils.atmosphereProtocol(request));
    }

    @Test
    void atmosphereProtocolFalseWhenHeaderNull() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        assertFalse(Utils.atmosphereProtocol(request));
    }

    @Test
    void atmosphereProtocolFalseWhenHeaderFalse() {
        Map<String, String> headers = new HashMap<>();
        headers.put(HeaderConfig.X_ATMO_PROTOCOL, "false");
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.headers(headers);
        assertFalse(Utils.atmosphereProtocol(request));
    }

    @Test
    void webSocketMessageTrueWhenAttributeSet() {
        AtmosphereResourceImpl resource = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.setAttribute(FrameworkConfig.WEBSOCKET_MESSAGE, "body");
        when(resource.getRequest(false)).thenReturn(request);

        assertTrue(Utils.webSocketMessage(resource));
    }

    @Test
    void webSocketMessageFalseWhenAttributeNull() {
        AtmosphereResourceImpl resource = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        when(resource.getRequest(false)).thenReturn(request);

        assertFalse(Utils.webSocketMessage(resource));
    }

    @Test
    void pathInfoReturnsServletPathPlusPathInfo() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.pathInfo("/info");
        request.servletPath("/app");

        assertEquals("/app/info", Utils.pathInfo(request));
    }

    @Test
    void pathInfoReturnsSlashWhenBothEmpty() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        assertEquals("/", Utils.pathInfo(request));
    }

    @Test
    void pathInfoReturnsServletPathWhenPathInfoEmpty() {
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.servletPath("/myapp");

        assertEquals("/myapp", Utils.pathInfo(request));
    }

    @Test
    void isRunningTestReturnsTrue() {
        // JUnit 5 is on the classpath during tests
        assertTrue(Utils.isRunningTest());
    }

    @Test
    void rawWebSocketReturnsTrueWhenUpgradeHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaders("Connection")).thenReturn(Collections.enumeration(Collections.singletonList("Upgrade")));
        when(request.getHeaders("connection")).thenReturn(Collections.enumeration(Collections.emptyList()));

        assertTrue(Utils.rawWebSocket(request));
    }

    @Test
    void rawWebSocketReturnsFalseWhenNoUpgradeHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaders("Connection")).thenReturn(Collections.enumeration(Collections.emptyList()));
        when(request.getHeaders("connection")).thenReturn(Collections.enumeration(Collections.emptyList()));

        assertFalse(Utils.rawWebSocket(request));
    }
}
