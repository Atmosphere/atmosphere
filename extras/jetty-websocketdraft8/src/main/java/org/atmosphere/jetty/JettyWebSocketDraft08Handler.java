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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.eclipse.jetty.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;

import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.DISCONNECT;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.MESSAGE;

/**
 * Jetty 7 & 8 WebSocket support.
 */
public class JettyWebSocketDraft08Handler implements WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocketDraft08Handler.class);

    private WebSocketProcessor webSocketProcessor;
    private final AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private final WebSocketProtocol webSocketProtocol;
    private org.atmosphere.websocket.WebSocket webSocket;
    

    public JettyWebSocketDraft08Handler(AtmosphereRequest request, AtmosphereFramework framework, WebSocketProtocol webSocketProtocol) {
        this.request = request;
        this.framework = framework;
        this.webSocketProtocol = webSocketProtocol;
    }

    @Override
    public void onConnect(org.eclipse.jetty.websocket.WebSocket.Outbound outbound) {

        logger.debug("WebSocket.onConnect (outbound)");
        try {
            webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
            webSocket = new JettyWebSocket(outbound, framework.getAtmosphereConfig());
            webSocketProcessor.open(webSocket, request);
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onMessage(byte frame, String data) {
        logger.trace("WebSocket.onMessage (frame/string)");
        webSocketProcessor.invokeWebSocketProtocol(webSocket, data);
        webSocketProcessor.notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent(data, MESSAGE, webSocket));
    }

    @Override
    public void onMessage(byte frame, byte[] data, int offset, int length) {
        logger.trace("WebSocket.onMessage (frame)");
        webSocketProcessor.invokeWebSocketProtocol(webSocket, new String(data, offset, length));
        try {
            webSocketProcessor.notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocket));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length) {
        logger.trace("WebSocket.onFragment");
        webSocketProcessor.invokeWebSocketProtocol(webSocket, new String(data, offset, length));
        try {
            webSocketProcessor.notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocket));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public void onDisconnect() {
        request.destroy();
        logger.trace("WebSocket.onDisconnect");
        webSocketProcessor.close(webSocket, 1000);
        webSocketProcessor.notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent("", DISCONNECT, webSocket));
    }

}
