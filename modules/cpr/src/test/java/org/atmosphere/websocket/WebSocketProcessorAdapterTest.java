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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketProcessorAdapterTest {

    private WebSocketProcessorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WebSocketProcessorAdapter();
    }

    @Test
    void configureReturnsSameInstance() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        WebSocketProcessor result = adapter.configure(config);
        assertSame(adapter, result);
    }

    @Test
    void configureWithNullReturnsSameInstance() {
        WebSocketProcessor result = adapter.configure(null);
        assertSame(adapter, result);
    }

    @Test
    void handshakeAlwaysReturnsTrue() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        assertTrue(adapter.handshake(request));
    }

    @Test
    void handshakeWithNullReturnsTrue() {
        assertTrue(adapter.handshake(null));
    }

    @Test
    void registerWebSocketHandlerReturnsSameInstance() {
        WebSocketProcessor.WebSocketHandlerProxy proxy = mock(WebSocketProcessor.WebSocketHandlerProxy.class);
        WebSocketProcessor result = adapter.registerWebSocketHandler("/test", proxy);
        assertSame(adapter, result);
    }

    @Test
    void openDoesNotThrow() throws IOException {
        WebSocket ws = mock(WebSocket.class);
        AtmosphereRequest req = mock(AtmosphereRequest.class);
        AtmosphereResponse resp = mock(AtmosphereResponse.class);
        assertDoesNotThrow(() -> adapter.open(ws, req, resp));
    }

    @Test
    void closeDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        assertDoesNotThrow(() -> adapter.close(ws, 1000));
    }

    @Test
    void destroyDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.destroy());
    }

    @Test
    void invokeWebSocketProtocolStringDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        assertDoesNotThrow(() -> adapter.invokeWebSocketProtocol(ws, "test message"));
    }

    @Test
    void invokeWebSocketProtocolInputStreamDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        assertDoesNotThrow(() -> adapter.invokeWebSocketProtocol(ws, new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void invokeWebSocketProtocolReaderDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        assertDoesNotThrow(() -> adapter.invokeWebSocketProtocol(ws, new StringReader("test")));
    }

    @Test
    void invokeWebSocketProtocolBytesDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        assertDoesNotThrow(() -> adapter.invokeWebSocketProtocol(ws, new byte[]{1, 2, 3}, 0, 3));
    }

    @Test
    void notifyListenerDoesNotThrow() {
        WebSocket ws = mock(WebSocket.class);
        @SuppressWarnings("unchecked")
        WebSocketEventListener.WebSocketEvent<String> event = mock(WebSocketEventListener.WebSocketEvent.class);
        assertDoesNotThrow(() -> adapter.notifyListener(ws, event));
    }
}
