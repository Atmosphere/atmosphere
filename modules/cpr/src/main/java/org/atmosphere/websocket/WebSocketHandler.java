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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A very simple {@link WebSocketProtocol} adapter class that implements all methods and expose a WebSocket API
 * close to the JavaScript Counterpart.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class WebSocketHandler implements WebSocketProtocol {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    /**
     * Invoked when a byte message is received.
     *
     * @param webSocket a {@link WebSocket}
     * @param data
     * @param offset
     * @param length
     */
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) {
    }

    /**
     * Invoked when a String message is received
     *
     * @param webSocket a {@link WebSocket}
     * @param data
     */
    public void onTextMessage(WebSocket webSocket, String data) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void configure(AtmosphereConfig config) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.resource().suspend(-1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(WebSocket webSocket) {
        webSocket.resource().resume();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.error(t.getMessage() + " Status {} Message {}", t.response().getStatus(), t.response().getStatusMessage());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final List<AtmosphereRequest> onMessage(WebSocket webSocket, String data) {
        onTextMessage(webSocket, data);
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data, int offset, int length) {
        onByteMessage(webSocket, data, offset, length);
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean inspectResponse() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final String handleResponse(AtmosphereResponse res, String message) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final byte[] handleResponse(AtmosphereResponse res, byte[] message, int offset, int length) {
        return new byte[0];
    }
}
