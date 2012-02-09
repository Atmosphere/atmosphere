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

import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocketAdapter;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Jetty 8 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty8WebSocket extends WebSocketAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Jetty8WebSocket.class);
    private final Connection connection;
    private final AtmosphereConfig config;

    public Jetty8WebSocket(Connection connection, AtmosphereConfig config) {
        this.connection = connection;
        this.config = config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeError(int errorCode, String message) throws IOException {
        logger.debug("{} {}", errorCode, message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void redirect(String location) throws IOException {
        logger.error("redirect not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(String data) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        connection.sendMessage(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] data) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        String s = config.getInitParameter(ApplicationConfig.WEBSOCKET_BLOB);
        if (s != null && Boolean.parseBoolean(s)) {
            connection.sendMessage(data, 0, data.length);
        } else {
            connection.sendMessage(new String(data, 0, data.length, "UTF-8"));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        String s = config.getInitParameter(ApplicationConfig.WEBSOCKET_BLOB);
        if (s != null && Boolean.parseBoolean(s)) {
            connection.sendMessage(data, offset, length);
        } else {
            connection.sendMessage(new String(data, offset, length, "UTF-8"));
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        logger.trace("WebSocket.close()");
        connection.disconnect();
    }
}
