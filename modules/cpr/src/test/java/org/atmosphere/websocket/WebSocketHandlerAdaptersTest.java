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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class WebSocketHandlerAdaptersTest {

    // --- WebSocketHandlerAdapter ---

    @Test
    void handlerOnByteMessageDoesNotThrow() {
        var adapter = new WebSocketHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onByteMessage(mock(WebSocket.class), new byte[]{1}, 0, 1));
    }

    @Test
    void handlerOnTextMessageDoesNotThrow() throws IOException {
        var adapter = new WebSocketHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onTextMessage(mock(WebSocket.class), "hello"));
    }

    @Test
    void handlerOnOpenDoesNotThrow() throws IOException {
        var adapter = new WebSocketHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onOpen(mock(WebSocket.class)));
    }

    @Test
    void handlerOnCloseDoesNotThrow() {
        var adapter = new WebSocketHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onClose(mock(WebSocket.class)));
    }

    @Test
    void handlerOnErrorDoesNotThrow() {
        var adapter = new WebSocketHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onError(mock(WebSocket.class),
                mock(WebSocketProcessor.WebSocketException.class)));
    }

    // --- WebSocketStreamingHandlerAdapter ---

    @Test
    void streamingOnBinaryStreamDoesNotThrow() {
        var adapter = new WebSocketStreamingHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onBinaryStream(mock(WebSocket.class),
                new ByteArrayInputStream(new byte[]{1})));
    }

    @Test
    void streamingOnTextStreamDoesNotThrow() {
        var adapter = new WebSocketStreamingHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onTextStream(mock(WebSocket.class),
                new StringReader("test")));
    }

    @Test
    void streamingOnByteMessageDoesNotThrow() {
        var adapter = new WebSocketStreamingHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onByteMessage(mock(WebSocket.class), new byte[]{1}, 0, 1));
    }

    @Test
    void streamingOnTextMessageDoesNotThrow() {
        var adapter = new WebSocketStreamingHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onTextMessage(mock(WebSocket.class), "hello"));
    }

    @Test
    void streamingOnOpenDoesNotThrow() {
        var adapter = new WebSocketStreamingHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onOpen(mock(WebSocket.class)));
    }

    @Test
    void streamingOnCloseDoesNotThrow() {
        var adapter = new WebSocketStreamingHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onClose(mock(WebSocket.class)));
    }

    @Test
    void streamingOnErrorDoesNotThrow() {
        var adapter = new WebSocketStreamingHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onError(mock(WebSocket.class),
                mock(WebSocketProcessor.WebSocketException.class)));
    }
}
