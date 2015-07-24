/*
* Copyright 2015 Async-IO.org
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

/**
 * WebSocket Ping/Pong Listener. Use this interface with {@link org.atmosphere.websocket.WebSocketProcessor}
 * or {@link org.atmosphere.websocket.WebSocketHandler} or {@link org.atmosphere.websocket.WebSocketStreamingHandler}
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketPingPongListener {
    /**
     * Handle WebSocket PONG
     *
     * @param webSocket {@link org.atmosphere.websocket.WebSocket}
     * @param payload   the received bytes
     * @param offset    the offset
     * @param length    the length
     */
    void onPong(WebSocket webSocket, byte[] payload, int offset, int length);

    /**
     * Handle WebSocket PING
     *
     * @param webSocket {@link org.atmosphere.websocket.WebSocket}
     * @param payload   the received bytes
     * @param offset    the offset
     * @param length    the length
     */
    void onPing(WebSocket webSocket, byte[] payload, int offset, int length);
}
