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
package org.atmosphere.webtransport;

import org.atmosphere.cpr.AtmosphereResourceEventListener;

/**
 * Listener for monitoring WebTransport session lifecycle events. Unlike
 * {@link org.atmosphere.websocket.WebSocketEventListener}, there is no
 * {@code CONTROL} event type — QUIC handles keep-alive at the transport layer.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebTransportEventListener extends AtmosphereResourceEventListener {

    /**
     * Invoked when a WebTransport session is opened.
     */
    void onOpen(WebTransportEvent<?> event);

    /**
     * Invoked when a message is received.
     */
    void onMessage(WebTransportEvent<?> event);

    /**
     * Invoked when the session is closed.
     */
    void onClose(WebTransportEvent<?> event);

    /**
     * Invoked when the session disconnects.
     */
    void onDisconnect(WebTransportEvent<?> event);

    /**
     * Invoked when a connection is established.
     */
    void onConnect(WebTransportEvent<?> event);

    /**
     * WebTransport lifecycle event. References a {@link WebTransportSession}
     * instead of a WebSocket.
     *
     * @param <T> the message type
     */
    final class WebTransportEvent<T> {

        /**
         * Event types for WebTransport sessions. No {@code CONTROL} or
         * {@code HANDSHAKE} — QUIC handles those at the transport layer.
         */
        public enum TYPE { CONNECT, OPEN, CLOSE, MESSAGE, DISCONNECT, EXCEPTION }

        private final T message;
        private final TYPE type;
        private final WebTransportSession session;

        public WebTransportEvent(T message, TYPE type, WebTransportSession session) {
            this.message = message;
            this.type = type;
            this.session = session;
        }

        public T message() {
            return message;
        }

        public WebTransportSession session() {
            return session;
        }

        public TYPE type() {
            return type;
        }

        @Override
        public String toString() {
            return "WebTransportEvent{" +
                    "message='" + message + '\'' +
                    ", type=" + type +
                    ", session=" + session +
                    '}';
        }
    }
}
