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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamingHttpProtocolTest {

    private StreamingHttpProtocol protocol;
    private WebSocket webSocket;
    private AtmosphereResourceImpl resource;
    private AtmosphereRequest request;

    @BeforeEach
    void setUp() {
        protocol = new StreamingHttpProtocol();
        webSocket = mock(WebSocket.class);
        resource = mock(AtmosphereResourceImpl.class);
        request = mock(AtmosphereRequest.class);

        when(webSocket.resource()).thenReturn(resource);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.getRequest()).thenReturn(request);
        when(resource.session()).thenReturn(null);
        when(webSocket.attributes()).thenReturn(Collections.emptyMap());
        when(request.getContextPath()).thenReturn("");
        when(request.getServletPath()).thenReturn("");
        when(request.getPathInfo()).thenReturn("/test");
        when(request.getRequestURI()).thenReturn("/test");
        when(request.requestURL()).thenReturn("http://localhost/test");
        when(request.headersMap()).thenReturn(new HashMap<>());
    }

    @Test
    void configureWithDefaults() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE)).thenReturn(null);

        protocol.configure(config);

        assertEquals("text/plain", protocol.contentType);
        assertEquals("POST", protocol.methodType);
        assertEquals("@@", protocol.delimiter);
    }

    @Test
    void configureWithCustomValues() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE)).thenReturn("application/json");
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD)).thenReturn("GET");
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER)).thenReturn("||");
        when(config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE)).thenReturn("true");

        protocol.configure(config);

        assertEquals("application/json", protocol.contentType);
        assertEquals("GET", protocol.methodType);
        assertEquals("||", protocol.delimiter);
    }

    @Test
    void onTextStreamReturnsRequestWithReader() {
        Reader reader = new StringReader("test data");
        List<AtmosphereRequest> result = protocol.onTextStream(webSocket, reader);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void onTextStreamNullResourceReturnsNull() {
        when(webSocket.resource()).thenReturn(null);

        Reader reader = new StringReader("test data");
        List<AtmosphereRequest> result = protocol.onTextStream(webSocket, reader);

        assertNull(result);
    }

    @Test
    void onBinaryStreamReturnsRequestWithInputStream() {
        InputStream stream = new ByteArrayInputStream("binary data".getBytes());
        List<AtmosphereRequest> result = protocol.onBinaryStream(webSocket, stream);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertNotNull(result.get(0));
    }

    @Test
    void onBinaryStreamNullResourceReturnsNull() {
        when(webSocket.resource()).thenReturn(null);

        InputStream stream = new ByteArrayInputStream("binary data".getBytes());
        List<AtmosphereRequest> result = protocol.onBinaryStream(webSocket, stream);

        assertNull(result);
    }

    @Test
    void onTextStreamWithNonTextContentTypePassesContentType() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE)).thenReturn("application/json");
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE)).thenReturn(null);
        protocol.configure(config);

        Reader reader = new StringReader("{}");
        List<AtmosphereRequest> result = protocol.onTextStream(webSocket, reader);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("application/json", result.get(0).getContentType());
    }

    @Test
    void onOpenDoesNotThrow() {
        protocol.onOpen(webSocket);
    }

    @Test
    void onCloseDoesNotThrow() {
        protocol.onClose(webSocket);
    }
}
