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

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class WebSocketEventListenerAdapterTest {

    private WebSocketEventListenerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WebSocketEventListenerAdapter();
    }

    @Test
    void onPreSuspendDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onPreSuspend(mock(AtmosphereResourceEvent.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onHandshakeDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onHandshake(mock(WebSocketEventListener.WebSocketEvent.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessageDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onMessage(mock(WebSocketEventListener.WebSocketEvent.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCloseWebSocketEventDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onClose(mock(WebSocketEventListener.WebSocketEvent.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onControlDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onControl(mock(WebSocketEventListener.WebSocketEvent.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onDisconnectWebSocketEventDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onDisconnect(mock(WebSocketEventListener.WebSocketEvent.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onConnectDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onConnect(mock(WebSocketEventListener.WebSocketEvent.class)));
    }

    @Test
    void onSuspendDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onSuspend(mock(AtmosphereResourceEvent.class)));
    }

    @Test
    void onResumeDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onResume(mock(AtmosphereResourceEvent.class)));
    }

    @Test
    void onHeartbeatDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onHeartbeat(mock(AtmosphereResourceEvent.class)));
    }

    @Test
    void onDisconnectResourceEventDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onDisconnect(mock(AtmosphereResourceEvent.class)));
    }

    @Test
    void onBroadcastDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onBroadcast(mock(AtmosphereResourceEvent.class)));
    }

    @Test
    void onThrowableDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onThrowable(mock(AtmosphereResourceEvent.class)));
    }

    @Test
    void onCloseResourceEventDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.onClose(mock(AtmosphereResourceEvent.class)));
    }
}
