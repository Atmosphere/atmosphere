/*
 * Copyright 20145 Async-IO.org
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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * Simple Adapter fpr {@link WebSocketProcessor}
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketProcessorAdapter implements WebSocketProcessor {
    @Override
    public WebSocketProcessor configure(AtmosphereConfig config) {
        return this;
    }

    @Override
    public boolean handshake(HttpServletRequest request) {
        return true;
    }

    @Override
    public WebSocketProcessor registerWebSocketHandler(String path, WebSocketHandlerProxy webSockethandler) {
        return this;
    }

    @Override
    public void open(WebSocket webSocket, AtmosphereRequest request, AtmosphereResponse response) throws IOException {
    }

    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, String webSocketMessage) {
    }

    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, InputStream stream) {
    }

    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, Reader reader) {
    }

    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, byte[] data, int offset, int length) {
    }

    @Override
    public void close(WebSocket webSocket, int closeCode) {
    }

    @Override
    public void notifyListener(WebSocket webSocket, WebSocketEventListener.WebSocketEvent webSocketEvent) {
    }

    @Override
    public void destroy() {
    }
}
