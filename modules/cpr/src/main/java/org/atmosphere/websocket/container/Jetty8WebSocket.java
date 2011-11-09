/*
* Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.websocket.container;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.websocket.WebSocketAdapter;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHttpServletResponse;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.eclipse.jetty.websocket.WebSocketServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Jetty 8 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty8WebSocket extends WebSocketAdapter implements WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(Jetty8WebSocket.class);
    private final Connection connection;
    private AtmosphereResource<?,?> atmosphereResource;

    public Jetty8WebSocket(Connection connection) {
        this.connection = connection;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeError(int errorCode, String message) throws IOException {
        logger.debug("{} {}", errorCode, message);
        if (atmosphereResource != null) {
            WebSocketHttpServletResponse r = WebSocketHttpServletResponse.class.cast(atmosphereResource.getResponse());
            r.setStatus(errorCode, message);
        }
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
        connection.sendMessage(data, 0, data.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        // Chrome doesn't like it, throwing: Received a binary frame which is not supported yet. So send a String instead
        connection.sendMessage(new String(data, offset, length, "UTF-8"));
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        logger.trace("WebSocket.close()");
        connection.disconnect();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAtmosphereResource(AtmosphereResource<?, ?> r) {
        atmosphereResource = r;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource<?, ?> atmosphereResource() {
        return atmosphereResource;
    }
}
