/*
* Copyright 2013 Jeanfrancois Arcand
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

import java.io.IOException;
import java.util.Arrays;

public class Grizzly2WebSocket extends WebSocket {

    private final org.glassfish.grizzly.websockets.WebSocket webSocket;

    public Grizzly2WebSocket(org.glassfish.grizzly.websockets.WebSocket webSocket, AtmosphereConfig config) {
        super(config);
        this.webSocket = webSocket;
    }

    @Override
    public boolean isOpen() {
        return webSocket.isConnected();
    }

    @Override
    public WebSocket write(String s) throws IOException {
        webSocket.send(s);
        return this;
    }

    @Override
    public WebSocket write(byte[] data, int offset, int length) throws IOException {
        webSocket.send(Arrays.copyOfRange(data, offset, length));
        return this;
    }

    @Override
    public void close() {
        logger.trace("WebSocket.close() for AtmosphereResource {}", resource() != null ? resource().uuid() : "null");
        webSocket.close();
    }
}
