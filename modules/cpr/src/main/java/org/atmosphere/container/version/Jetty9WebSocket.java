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
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.io.WebSocketBlockingConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class Jetty9WebSocket extends WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(Jetty9WebSocket.class);   
    private final Session webSocketConnection;
    private final WebSocketBlockingConnection blockingConnection;
    private final AtomicBoolean firstWrite = new AtomicBoolean(false);
    
    public Jetty9WebSocket(Session webSocketConnection, AtmosphereConfig config) {
        super(config);
        this.webSocketConnection = webSocketConnection;
        blockingConnection = new WebSocketBlockingConnection(webSocketConnection);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        if (!firstWrite.get()) {
            logger.debug("The WebSocket handshake succeeded but the dispatched URI failed with status {} : {} " +
                    "The WebSocket connection is still open and client can continue sending messages.", errorCode + " " + message, retrieveUUID());
        } else {
            logger.debug("{} {}", errorCode, message);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket redirect(AtmosphereResponse r, String location) throws IOException {
        logger.error("WebSocket Redirect not supported");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, String data) throws IOException {
        logger.trace("WebSocket.write() for {}", resource() != null ? resource().uuid() : "");
        firstWrite.set(true);

        if (!webSocketConnection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");

        if (binaryWrite) {
            byte[] b = webSocketResponseFilter.filter(r, data.getBytes(resource().getResponse().getCharacterEncoding()));
            if (b != null) {
                blockingConnection.write(b, 0, b.length);
            }
        } else {
            String s = webSocketResponseFilter.filter(r, data);
            if (s != null) {
                blockingConnection.write(s);
            }
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, byte[] data) throws IOException {
        firstWrite.set(true);

        if (!webSocketConnection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write() for {}", resource() != null ? resource().uuid() : "");

        if (binaryWrite) {
            byte[] b = webSocketResponseFilter.filter(r, data);
            if (b != null) {
                blockingConnection.write(b, 0, b.length);
            }
        } else {
            byte[] s = webSocketResponseFilter.filter(r, data);
            if (s != null) {
                blockingConnection.write(new String(s, r.getCharacterEncoding()));
            }
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
        firstWrite.set(true);

        if (!webSocketConnection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write() for {}", resource() != null ? resource().uuid() : "");

        if (binaryWrite) {
            if (!WebSocketResponseFilter.NoOpsWebSocketResponseFilter.class.isAssignableFrom(webSocketResponseFilter.getClass())) {
                byte[] b = webSocketResponseFilter.filter(r, data, offset, length);
                if (b != null) {
                    blockingConnection.write(b, 0, b.length);
                }
            } else {
                blockingConnection.write(data, offset, length);
            }
        } else {
            String s = webSocketResponseFilter.filter(r, new String(data, offset, length, "UTF-8"));
            if (s != null) {
                blockingConnection.write(s);
            }
        }
        lastWrite = System.currentTimeMillis();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close(AtmosphereResponse r) throws IOException {
        logger.trace("WebSocket.close() for AtmosphereResource {}", resource() != null ? resource().uuid() : "null");
        webSocketConnection.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket flush(AtmosphereResponse r) throws IOException {
        logger.trace("WebSocket.flush() not supported by Jetty");
        return this;
    }

    @Override
    public String toString() {
        return blockingConnection.toString();
    }
}
