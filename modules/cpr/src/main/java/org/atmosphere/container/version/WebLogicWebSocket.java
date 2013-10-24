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
import weblogic.websocket.WebSocketConnection;

import java.io.IOException;
import java.util.Arrays;

public class WebLogicWebSocket extends WebSocket {

    private final WebSocketConnection webSocketConnection;

    public WebLogicWebSocket(WebSocketConnection webSocketConnection, AtmosphereConfig config) {
        super(config);
        this.webSocketConnection = webSocketConnection;
    }


    @Override
    public boolean isOpen() {
        return webSocketConnection.isOpen();
    }

    @Override
    public WebSocket write(String s) throws IOException {
        webSocketConnection.send(s);
        return this;
    }

    @Override
    public WebSocket write(byte[] b, int offset, int length) throws IOException {
        webSocketConnection.send(Arrays.copyOfRange(b, offset, length));
        return this;
    }

    @Override
    public void close() {
        try {
            webSocketConnection.close(1005);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
