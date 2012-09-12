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
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GrizzlyWebSocket extends WebSocket {

    private final com.sun.grizzly.websockets.WebSocket webSocket;

    public GrizzlyWebSocket(com.sun.grizzly.websockets.WebSocket webSocket, AtmosphereConfig config) {
        super(config);
        this.webSocket = webSocket;
    }

    @Override
    public boolean isOpen() {
        return webSocket.isConnected();
    }

    @Override
    public void write(String s) throws IOException {
        webSocket.send(s);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        webSocket.send(Arrays.copyOfRange(data, offset, length));
    }

    @Override
    public void close() {
        webSocket.close();
    }
}
