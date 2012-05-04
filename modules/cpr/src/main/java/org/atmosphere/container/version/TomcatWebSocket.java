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

import org.apache.catalina.websocket.WsOutbound;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.websocket.WebSocketAdapter;
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tomcat WebSocket Support
 *
 * @author Jeanfrancois Arcand
 */
public class TomcatWebSocket extends WebSocketAdapter {

    private final WsOutbound outbound;
    private static final Logger logger = LoggerFactory.getLogger(TomcatWebSocket.class);
    private final AtmosphereConfig config;
    private final AtomicBoolean firstWrite = new AtomicBoolean(false);

    public TomcatWebSocket(WsOutbound outbound, AtmosphereConfig config) {
        super(config);
        this.outbound = outbound;
        this.config = config;
    }

    @Override
    public void redirect(String location) throws IOException {
        logger.error("WebSocket Redirect not supported");
    }

    @Override
    public void writeError(int errorCode, String message) throws IOException {
        if (!firstWrite.get()) {
            logger.debug("The WebSocket handshake succeeded but the dispatched URI failed {}:{}. " +
                    "The WebSocket connection is still open and client can continue sending messages.", message, errorCode);
        } else {
            logger.debug("{} {}", errorCode, message);
        }
    }

    @Override
    public void write(String data) throws IOException {
        firstWrite.set(true);
        logger.trace("WebSocket.write()");

        if (binaryWrite) {
            outbound.writeBinaryMessage(ByteBuffer.wrap(
                    webSocketResponseFilter.filter(data).getBytes(resource().getResponse().getCharacterEncoding())));
        } else {
            outbound.writeTextMessage(CharBuffer.wrap(webSocketResponseFilter.filter(data)));
        }
        lastWrite = System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] data) throws IOException {
        firstWrite.set(true);
        logger.trace("WebSocket.write()");

        if (binaryWrite) {
            outbound.writeBinaryMessage(ByteBuffer.wrap(webSocketResponseFilter.filter(data)));
        } else {
            outbound.writeTextMessage(CharBuffer.wrap(
                    webSocketResponseFilter.filter(new String(data, resource().getResponse().getCharacterEncoding()))));
        }
        lastWrite = System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        firstWrite.set(true);
        logger.trace("WebSocket.write()");

        if (binaryWrite) {
            if (!WebSocketResponseFilter.NoOpsWebSocketResponseFilter.class.isAssignableFrom(webSocketResponseFilter.getClass())) {
                byte[] b = webSocketResponseFilter.filter(data, offset, length);
                outbound.writeBinaryMessage(ByteBuffer.wrap(b));
            } else {
                outbound.writeBinaryMessage(ByteBuffer.wrap(data, offset, length));
            }
        } else {
            outbound.writeTextMessage(CharBuffer.wrap(
                    webSocketResponseFilter.filter(new String(data, offset, length, resource().getResponse().getCharacterEncoding()))));
        }

        lastWrite = System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        logger.trace("WebSocket.close()");
        outbound.close(1005, ByteBuffer.wrap(new byte[0]));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() throws IOException {
    }

    @Override
    public String toString() {
        return outbound.toString();
    }
}
