/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.cpr.AtmosphereResourceEventListener;

/**
 * A listener for monitoring what's occurring on a WebSocket, independently of the underlying implementation.
 * The {@link WebSocketEvent#webSocket} can be used to directly write bytes.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketEventListener extends AtmosphereResourceEventListener{

    /**
     * When the hanshake occurs
     * @param event {@link WebSocketEvent}
     */
    AtmosphereResourceEventListener onHandshake(WebSocketEvent event);

    /**
     * When a message is sent
     * @param event {@link WebSocketEvent}
     */
    AtmosphereResourceEventListener onMessage(WebSocketEvent event);

    /**
     * When the close occurs
     * @param event {@link WebSocketEvent}
     */
    AtmosphereResourceEventListener onClose(WebSocketEvent event);

    /**
     * When the control occurs
     * @param event {@link WebSocketEvent}
     */
    AtmosphereResourceEventListener onControl(WebSocketEvent event);

    /**
     * When the disconnect occurs
     * @param event {@link WebSocketEvent}
     */
    AtmosphereResourceEventListener onDisconnect(WebSocketEvent event);

     /**
     * When the connect occurs
     * @param event {@link WebSocketEvent}
     */
    AtmosphereResourceEventListener onConnect(WebSocketEvent event);


    public static final class WebSocketEvent {
        public enum TYPE { CONNECT, HANDSHAKE, CLOSE, MESSAGE, CONTROL, DISCONNECT}
        private final String message;
        private final TYPE type;
        private final WebSocket webSocket;

        public WebSocketEvent(String message, TYPE type, WebSocket webSocket) {
            this.message = message;
            this.type = type;
            this.webSocket = webSocket;
        }

        /**
         * The received message
         * @return received message
         */
        public String message(){
            return message;
        }

        /**
         * The WebSocket.
         * @return
         */
        public WebSocket webSocket(){
            return webSocket;
        }

        /**
         * The type of the last Websocket's event.
         * @return the type.
         */
        public TYPE type() {
            return type;
        }

        @Override
        public String toString() {
            return "WebSocketEvent{" +
                    "message='" + message + '\'' +
                    ", type=" + type +
                    ", webSocket=" + webSocket +
                    '}';
        }

    }
}
