/*
* Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.container.version;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

public final class GrizzlyWebSocket extends WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(GrizzlyWebSocket.class);
    private final com.sun.grizzly.websockets.WebSocket webSocket;

    public GrizzlyWebSocket(com.sun.grizzly.websockets.WebSocket webSocket, AtmosphereConfig config) {
        super(config);
        this.webSocket = webSocket;
    }

    @Override
    public boolean isOpen() {
        return webSocket.isConnected();
    }

    @Override
    public WebSocket write(String s) throws IOException {
        logger.trace("WebSocket.write() for {}", resource() != null ? resource().uuid() : "");
        webSocket.send(s);
        return this;
    }

    @Override
    public WebSocket write(byte[] data, int offset, int length) throws IOException {
        logger.trace("WebSocket.write() for {}", resource() != null ? resource().uuid() : "");
        webSocket.send(Arrays.copyOfRange(data, offset, length));
        return this;
    }

    @Override
    public void close() {
        logger.trace("WebSocket.close() for AtmosphereResource {}", resource() != null ? resource().uuid() : "null");
        webSocket.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket flush(AtmosphereResponse r) throws IOException {
        logger.trace("WebSocket.flush() not supported by Grizzly");
        return this;
    }
}
