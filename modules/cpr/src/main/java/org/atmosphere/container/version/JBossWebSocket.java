/*
 * Copyright 2013 Péter Miklós
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
import org.atmosphere.jboss.as.websockets.WebSocket;
import org.atmosphere.jboss.websockets.frame.BinaryFrame;
import org.atmosphere.jboss.websockets.frame.TextFrame;

import java.io.IOException;
import java.util.Arrays;

/**
 * Adapts a JBoss {@link WebSocket} to an Atmosphere {@link org.atmosphere.websocket.WebSocket}.
 * 
 * @author Péter Miklós
 */
public class JBossWebSocket extends org.atmosphere.websocket.WebSocket {

    private final WebSocket webSocket;

    public JBossWebSocket(WebSocket webSocket, AtmosphereConfig config) {
        super(config);
        this.webSocket = webSocket;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public org.atmosphere.websocket.WebSocket write(String s) throws IOException {
        webSocket.writeFrame(TextFrame.from(s));
        return this;
    }

    @Override
    public org.atmosphere.websocket.WebSocket write(byte[] b, int offset, int length) throws IOException {
        webSocket.writeFrame(BinaryFrame.from(Arrays.copyOfRange(b, offset, length)));
        return this;
    }

    @Override
    public void close() {
        try {
            logger.trace("WebSocket.close() for AtmosphereResource {}", resource() != null ? resource().uuid() : "null");
            webSocket.closeSocket();
        } catch (IOException e) {
            logger.trace("Error closing websocket.", e);
        }
    }
}