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
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketStreamingHandlerAdapterTest {

    private final WebSocketStreamingHandlerAdapter adapter = new WebSocketStreamingHandlerAdapter();

    @Test
    void implementsWebSocketStreamingHandler() {
        assertInstanceOf(WebSocketStreamingHandler.class, adapter);
    }

    @Test
    void implementsWebSocketHandler() {
        assertInstanceOf(WebSocketHandler.class, adapter);
    }

    @Test
    void subclassCanOverrideOnBinaryStream() throws IOException {
        var captured = new AtomicReference<InputStream>();
        var custom = new WebSocketStreamingHandlerAdapter() {
            @Override
            public void onBinaryStream(WebSocket webSocket, InputStream inputStream) {
                captured.set(inputStream);
            }
        };
        var input = new ByteArrayInputStream(new byte[]{10, 20, 30});
        custom.onBinaryStream(mock(WebSocket.class), input);
        assertEquals(input, captured.get());
    }

    @Test
    void subclassCanOverrideOnTextStream() throws IOException {
        var captured = new AtomicReference<Reader>();
        var custom = new WebSocketStreamingHandlerAdapter() {
            @Override
            public void onTextStream(WebSocket webSocket, Reader reader) {
                captured.set(reader);
            }
        };
        var reader = new StringReader("stream-data");
        custom.onTextStream(mock(WebSocket.class), reader);
        assertEquals(reader, captured.get());
    }

    @Test
    void subclassCanOverrideOnTextMessage() throws IOException {
        var captured = new AtomicReference<String>();
        var custom = new WebSocketStreamingHandlerAdapter() {
            @Override
            public void onTextMessage(WebSocket webSocket, String data) {
                captured.set(data);
            }
        };
        custom.onTextMessage(mock(WebSocket.class), "hello");
        assertEquals("hello", captured.get());
    }

    @Test
    void subclassCanOverrideOnByteMessage() {
        var called = new AtomicBoolean(false);
        var custom = new WebSocketStreamingHandlerAdapter() {
            @Override
            public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) {
                called.set(true);
            }
        };
        custom.onByteMessage(mock(WebSocket.class), new byte[]{1, 2}, 0, 2);
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnOpen() throws IOException {
        var called = new AtomicBoolean(false);
        var custom = new WebSocketStreamingHandlerAdapter() {
            @Override
            public void onOpen(WebSocket webSocket) {
                called.set(true);
            }
        };
        custom.onOpen(mock(WebSocket.class));
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnClose() {
        var called = new AtomicBoolean(false);
        var custom = new WebSocketStreamingHandlerAdapter() {
            @Override
            public void onClose(WebSocket webSocket) {
                called.set(true);
            }
        };
        custom.onClose(mock(WebSocket.class));
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnError() {
        var called = new AtomicBoolean(false);
        var custom = new WebSocketStreamingHandlerAdapter() {
            @Override
            public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
                called.set(true);
            }
        };
        custom.onError(mock(WebSocket.class), mock(WebSocketProcessor.WebSocketException.class));
        assertTrue(called.get());
    }

    @Test
    void onBinaryStreamWithEmptyStream() {
        assertDoesNotThrow(() ->
                adapter.onBinaryStream(mock(WebSocket.class), new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void onTextStreamWithEmptyReader() {
        assertDoesNotThrow(() ->
                adapter.onTextStream(mock(WebSocket.class), new StringReader("")));
    }

    @Test
    void onTextMessageWithEmptyString() {
        assertDoesNotThrow(() ->
                adapter.onTextMessage(mock(WebSocket.class), ""));
    }

    @Test
    void onByteMessageWithZeroLength() {
        assertDoesNotThrow(() ->
                adapter.onByteMessage(mock(WebSocket.class), new byte[0], 0, 0));
    }
}
