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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class WebSocketEventTest {

    @Test
    void constructorRetainsFields() {
        var ws = mock(WebSocket.class);
        var event = new WebSocketEventListener.WebSocketEvent<>("hello",
                WebSocketEventListener.WebSocketEvent.TYPE.MESSAGE, ws);

        assertEquals("hello", event.message());
        assertEquals(WebSocketEventListener.WebSocketEvent.TYPE.MESSAGE, event.type());
        assertEquals(ws, event.webSocket());
    }

    @Test
    void nullMessageAllowed() {
        var event = new WebSocketEventListener.WebSocketEvent<>(null,
                WebSocketEventListener.WebSocketEvent.TYPE.CLOSE, null);
        assertNull(event.message());
        assertNull(event.webSocket());
    }

    @Test
    void allEventTypes() {
        var types = WebSocketEventListener.WebSocketEvent.TYPE.values();
        assertEquals(8, types.length);
    }

    @Test
    void toStringContainsFields() {
        var ws = mock(WebSocket.class);
        var event = new WebSocketEventListener.WebSocketEvent<>("data",
                WebSocketEventListener.WebSocketEvent.TYPE.HANDSHAKE, ws);

        String str = event.toString();
        assertEquals(true, str.contains("data"));
        assertEquals(true, str.contains("HANDSHAKE"));
    }

    @Test
    void connectType() {
        assertEquals(WebSocketEventListener.WebSocketEvent.TYPE.CONNECT,
                WebSocketEventListener.WebSocketEvent.TYPE.valueOf("CONNECT"));
    }

    @Test
    void streamType() {
        assertEquals(WebSocketEventListener.WebSocketEvent.TYPE.STREAM,
                WebSocketEventListener.WebSocketEvent.TYPE.valueOf("STREAM"));
    }

    @Test
    void exceptionType() {
        assertEquals(WebSocketEventListener.WebSocketEvent.TYPE.EXCEPTION,
                WebSocketEventListener.WebSocketEvent.TYPE.valueOf("EXCEPTION"));
    }
}
