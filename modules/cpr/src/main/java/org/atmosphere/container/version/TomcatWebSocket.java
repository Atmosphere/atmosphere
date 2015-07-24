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
package org.atmosphere.container.version;

import org.apache.catalina.websocket.WsOutbound;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tomcat WebSocket Support
 *
 * @author Jeanfrancois Arcand
 */
public class TomcatWebSocket extends WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(TomcatWebSocket.class);

    private final WsOutbound outbound;
    private AtomicBoolean isOpen = new AtomicBoolean(true);
    private AtomicBoolean isClosed = new AtomicBoolean();
    private final ByteBuffer closeCode = ByteBuffer.wrap(new byte[0]);

    public TomcatWebSocket(WsOutbound outbound, AtmosphereConfig config) {
        super(config);
        this.outbound = outbound;
    }

    @Override
    public boolean isOpen() {
        return isOpen.get();
    }

    @Override
    public WebSocket write(String s) throws IOException {
        logger.trace("WebSocket.write() for {}", uuid);
        outbound.writeTextMessage(CharBuffer.wrap(s));
        return this;
    }

    @Override
    public WebSocket write(byte[] b, int offset, int length) throws IOException {
        logger.trace("WebSocket.write() for {}", uuid);
        outbound.writeBinaryMessage(ByteBuffer.wrap(b, offset, length));
        return this;
    }

    @Override
    public void close() {
        close(uuid());
    }

    @Override
    public void close(AtmosphereResponse r) throws IOException {
        close(r.uuid());
    }

    void close(String uuid) {
        isOpen.set(false);

        if (!isClosed.getAndSet(true)) {
            try {
                logger.trace("WebSocket.close() for AtmosphereResource {}", uuid);
                outbound.close(1000, closeCode);
            } catch (IOException e) {
                // https://github.com/Atmosphere/atmosphere/issues/1646
                // Tomcat will go in limbo
                logger.trace("", e);
            }
        } else {
            logger.trace("Already closed {}", uuid);
        }
    }


    @Override
    public WebSocket flush(AtmosphereResponse r) throws IOException {
        outbound.flush();
        return this;
    }

    @Override
    public String toString() {
        return outbound.toString();
    }
}
