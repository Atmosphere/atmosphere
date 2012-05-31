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
package org.atmosphere.container.version;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jetty 8 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty8WebSocket extends WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(Jetty8WebSocket.class);
    private final Connection connection;
    private final AtmosphereConfig config;
    private final AtomicBoolean firstWrite = new AtomicBoolean(false);

    public Jetty8WebSocket(Connection connection, AtmosphereConfig config) {
        super(config);
        this.connection = connection;
        this.config = config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        if (!firstWrite.get()) {
            logger.debug("The WebSocket handshake succeeded but the dispatched URI failed {}:{}. " +
                    "The WebSocket connection is still open and client can continue sending messages.", message, errorCode);
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
        firstWrite.set(true);
        if (!connection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");

        if (binaryWrite) {
            byte[] b = webSocketResponseFilter.filter(r, data.getBytes(resource().getResponse().getCharacterEncoding()));
            if (b != null) {
                connection.sendMessage(b, 0, b.length);
            }
        } else {
            String s = webSocketResponseFilter.filter(r, data);
            if (s != null) {
                connection.sendMessage(s);
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
        if (!connection.isOpen()) throw new IOException("Connection remotely closed");

        logger.trace("WebSocket.write()");
        if (binaryWrite) {
            byte[] b = webSocketResponseFilter.filter(r, data);
            if (b != null) {
                connection.sendMessage(b, 0, b.length);
            }
        } else {
            byte[] s = webSocketResponseFilter.filter(r, data);
            if (s != null) {
                connection.sendMessage(new String(s, r.getCharacterEncoding()));
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
        if (!connection.isOpen()) throw new IOException("Connection remotely closed");

        logger.trace("WebSocket.write()");
        if (binaryWrite) {
            if (!WebSocketResponseFilter.NoOpsWebSocketResponseFilter.class.isAssignableFrom(webSocketResponseFilter.getClass())) {
                byte[] b = webSocketResponseFilter.filter(r, data, offset, length);
                if (b != null) {
                    connection.sendMessage(b, 0, b.length);
                }
            } else {
                connection.sendMessage(data, offset, length);
            }
        } else {
            String s = webSocketResponseFilter.filter(r, new String(data, offset, length, "UTF-8"));
            if (s != null) {
                connection.sendMessage(s);
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
        logger.trace("WebSocket.close()");
        connection.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket flush(AtmosphereResponse r) throws IOException {
        return this;
    }

    @Override
    public String toString() {
        return connection.toString();
    }
}
