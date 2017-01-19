/*
* Copyright 2017 Async-IO.org
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

import java.io.IOException;

/**
 * A very simple interface adapter class that implements all methods and expose a WebSocket API
 * close to the JavaScript Counterpart.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketHandler {

    /**
     * Invoked when a byte message is received.
     *
     * @param webSocket a {@link WebSocket}
     * @param data
     * @param offset
     * @param length
     */
    void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) throws IOException;

    /**
     * Invoked when a String message is received
     *
     * @param webSocket a {@link WebSocket}
     * @param data
     */
    void onTextMessage(WebSocket webSocket, String data) throws IOException;

    /**
     * Invoked when a {@link WebSocket} is opened.
     *
     * @param webSocket
     */
    void onOpen(WebSocket webSocket) throws IOException;

    /**
     * Invoked when a {@link WebSocket} is closed.
     *
     * @param webSocket
     */
    void onClose(WebSocket webSocket);

    /**
     * Invoked when a {@link WebSocket} produces an error.
     *
     * @param webSocket
     */
    void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t);
}
