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
package org.atmosphere.jetty;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Jetty 7.1/2 & 8 < M3 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyWebSocket extends WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocket.class);
    private final Outbound outbound;
    private final byte frame = 0x00;

    public JettyWebSocket(Outbound outbound, AtmosphereConfig config) {
        super(config);
        this.outbound = outbound;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        logger.debug("{} {}", errorCode, message);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket redirect(AtmosphereResponse r, String location) throws IOException {
        logger.error("redirect not supported");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, String data) throws IOException {
        if (!outbound.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        outbound.sendMessage(frame, data);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, byte[] data) throws IOException {
        if (!outbound.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        outbound.sendMessage(frame, data, 0, data.length);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
        if (!outbound.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        outbound.sendMessage(frame, data, offset, length);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close(AtmosphereResponse r) throws IOException {
        outbound.disconnect();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocket flush(AtmosphereResponse r) throws IOException {
        return this;
    }
}
