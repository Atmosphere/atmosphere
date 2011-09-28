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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Jetty 8 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty8WebSocketSupport implements WebSocketSupport {

    private static final Logger logger = LoggerFactory.getLogger(Jetty8WebSocketSupport.class);
    private final Connection connection;

    public Jetty8WebSocketSupport(Connection connection) {
        this.connection = connection;
    }

    public void writeError(int errorCode, String message) throws IOException {
    }

    public void redirect(String location) throws IOException {
    }

    public void write(byte frame, String data) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection closed");
        logger.debug("WebSocket.write()");
        connection.sendMessage(data);
    }

    public void write(byte frame, byte[] data) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection closed");
        logger.debug("WebSocket.write()");
        connection.sendMessage(data, 0, data.length);
    }

    public void write(byte frame, byte[] data, int offset, int length) throws IOException {
        if (!connection.isOpen()) throw new IOException("Connection closed");
        logger.debug("WebSocket.write()");
        // Chrome doesn't like it, throwing: Received a binary frame which is not supported yet. So send a String instead
        connection.sendMessage(new String(data, offset, length, "UTF-8"));
    }

    public void close() throws IOException {
        logger.debug("WebSocket.close()");
        connection.disconnect();
    }


}
