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
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.eclipse.jetty.websocket.WebSocket.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jetty 7.1/2 & 8 < M3 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyWebSocket extends WebSocket {

    private final Outbound outbound;

    public JettyWebSocket(Outbound outbound, AtmosphereConfig config) {
        super(config);
        this.outbound = outbound;
    }

    @Override
    public String toString() {
        return outbound.toString();
    }

    @Override
    public boolean isOpen() {
        return outbound.isOpen();
    }

    @Override
    public void write(String s) throws IOException {
        outbound.sendMessage(s);
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
        outbound.sendMessage((byte) 0x00, b, offset, length);
    }

    @Override
    public void close() {
        outbound.disconnect();
    }
}
