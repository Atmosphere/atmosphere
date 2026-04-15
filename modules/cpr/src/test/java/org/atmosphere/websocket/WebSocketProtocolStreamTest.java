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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketProtocolStreamTest {

    @Test
    void implementsWebSocketProtocol() {
        assertTrue(WebSocketProtocol.class.isAssignableFrom(WebSocketProtocolStream.class));
    }

    @Test
    void onTextStreamReturnsRequestsFromImplementation() {
        WebSocketProtocolStream impl = new TestWebSocketProtocolStream();
        var webSocket = mock(WebSocket.class);
        var reader = new StringReader("hello");

        List<AtmosphereRequest> result = impl.onTextStream(webSocket, reader);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void onBinaryStreamReturnsRequestsFromImplementation() {
        WebSocketProtocolStream impl = new TestWebSocketProtocolStream();
        var webSocket = mock(WebSocket.class);
        var stream = new ByteArrayInputStream(new byte[]{1, 2, 3});

        List<AtmosphereRequest> result = impl.onBinaryStream(webSocket, stream);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void onTextStreamHandlesEmptyReader() {
        WebSocketProtocolStream impl = new EmptyWebSocketProtocolStream();
        var webSocket = mock(WebSocket.class);
        var reader = new StringReader("");

        List<AtmosphereRequest> result = impl.onTextStream(webSocket, reader);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void onBinaryStreamHandlesEmptyStream() {
        WebSocketProtocolStream impl = new EmptyWebSocketProtocolStream();
        var webSocket = mock(WebSocket.class);
        var stream = new ByteArrayInputStream(new byte[0]);

        List<AtmosphereRequest> result = impl.onBinaryStream(webSocket, stream);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void interfaceExtendsWebSocketProtocol() {
        // Verify the interface hierarchy
        Class<?>[] interfaces = WebSocketProtocolStream.class.getInterfaces();
        assertEquals(1, interfaces.length);
        assertEquals(WebSocketProtocol.class, interfaces[0]);
    }

    /**
     * A test implementation that returns a single-element list for non-empty input.
     */
    private static class TestWebSocketProtocolStream implements WebSocketProtocolStream {
        @Override
        public List<AtmosphereRequest> onTextStream(WebSocket webSocket, Reader r) {
            return List.of(mock(AtmosphereRequest.class));
        }

        @Override
        public List<AtmosphereRequest> onBinaryStream(WebSocket webSocket, InputStream stream) {
            return List.of(mock(AtmosphereRequest.class));
        }

        @Override
        public List<AtmosphereRequest> onMessage(WebSocket webSocket, String data) {
            return Collections.emptyList();
        }

        @Override
        public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data, int offset, int length) {
            return Collections.emptyList();
        }

        @Override
        public void onOpen(WebSocket webSocket) { }

        @Override
        public void onClose(WebSocket webSocket) { }

        @Override
        public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) { }

        @Override
        public void configure(AtmosphereConfig config) { }
    }

    /**
     * A test implementation that returns empty lists.
     */
    private static class EmptyWebSocketProtocolStream implements WebSocketProtocolStream {
        @Override
        public List<AtmosphereRequest> onTextStream(WebSocket webSocket, Reader r) {
            return Collections.emptyList();
        }

        @Override
        public List<AtmosphereRequest> onBinaryStream(WebSocket webSocket, InputStream stream) {
            return Collections.emptyList();
        }

        @Override
        public List<AtmosphereRequest> onMessage(WebSocket webSocket, String data) {
            return Collections.emptyList();
        }

        @Override
        public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data, int offset, int length) {
            return Collections.emptyList();
        }

        @Override
        public void onOpen(WebSocket webSocket) { }

        @Override
        public void onClose(WebSocket webSocket) { }

        @Override
        public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) { }

        @Override
        public void configure(AtmosphereConfig config) { }
    }
}
