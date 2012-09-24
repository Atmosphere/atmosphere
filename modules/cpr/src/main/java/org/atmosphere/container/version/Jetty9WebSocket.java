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
import org.atmosphere.websocket.WebSocket;
import org.eclipse.jetty.websocket.core.api.WebSocketConnection;
import org.eclipse.jetty.websocket.core.api.io.WebSocketBlockingConnection;

import java.io.IOException;

public class Jetty9WebSocket extends WebSocket {

    private final WebSocketConnection webSocketConnection;
    private final WebSocketBlockingConnection blockingConnection;

    public Jetty9WebSocket(WebSocketConnection webSocketConnection, AtmosphereConfig config) {
        super(config);
        this.webSocketConnection = webSocketConnection;
        blockingConnection = new WebSocketBlockingConnection(webSocketConnection);
    }

    @Override
    public boolean isOpen() {
        return webSocketConnection.isOpen();
    }

    @Override
    public void write(String s) throws IOException {
        blockingConnection.write(s);
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
        blockingConnection.write(b, offset, length);
    }

    @Override
    public void close() {
        webSocketConnection.close();
    }
}
