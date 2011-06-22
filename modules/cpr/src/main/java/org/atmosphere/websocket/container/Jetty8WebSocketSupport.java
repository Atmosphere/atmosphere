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

import org.atmosphere.websocket.WebSocketSupport;
import org.eclipse.jetty.websocket.WebSocket.Connection;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jetty 8 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty8WebSocketSupport implements WebSocketSupport
{

    private final Connection connection;

    private AtomicBoolean webSocketLatencyCheck = new AtomicBoolean(false);

    public Jetty8WebSocketSupport(Connection connection) {
        this.connection = connection;
    }

    public void writeError(int errorCode, String message) throws IOException {
    }

    public void redirect(String location) throws IOException {
    }

    public void write(byte frame, String data) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection closed");
        connection.sendMessage(data);
    }

    public void write(byte frame, byte[] data) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection closed");
        connection.sendMessage(data, 0, data.length);
    }

    public void write(byte frame, byte[] data, int offset, int length) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection closed");
        connection.sendMessage(data, offset, length);
    }

    public void close() throws IOException {
        connection.disconnect();
    }



}
