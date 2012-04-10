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
package org.atmosphere.container;

import org.apache.catalina.websocket.MessageInbound;
import org.apache.catalina.websocket.WsOutbound;
import org.atmosphere.container.version.TomcatWebSocket;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CLOSE;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CONNECT;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.MESSAGE;

public class TomcatWebSocketHandler extends MessageInbound {

    private static final Logger logger = LoggerFactory.getLogger(TomcatWebSocketHandler.class);

    private WebSocketProcessor webSocketProcessor;
    private final AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private final WebSocketProtocol webSocketProtocol;

    public TomcatWebSocketHandler(AtmosphereRequest request, AtmosphereFramework framework, WebSocketProtocol webSocketProtocol) {
        this.request = request;
        this.framework = framework;
        this.webSocketProtocol = webSocketProtocol;
    }

    @Override
    protected void onOpen(WsOutbound outbound) {
        logger.trace("WebSocket.onOpen.");
        try {
            webSocketProcessor = new WebSocketProcessor(framework, new TomcatWebSocket(outbound, framework.getAtmosphereConfig()), webSocketProtocol);
            webSocketProcessor.dispatch(request);
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CONNECT, webSocketProcessor.webSocket()));
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    protected void onClose(int closeCode) {
        request.destroy();
        if (webSocketProcessor == null) return;

        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CLOSE, webSocketProcessor.webSocket()));
        webSocketProcessor.close(closeCode);
    }

    @Override
    protected void onBinaryMessage(ByteBuffer message) throws IOException {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(message.array(), 0, message.array().length);
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(message.array(), "UTF-8"), MESSAGE, webSocketProcessor.webSocket()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    protected void onTextMessage(CharBuffer message) throws IOException {
        logger.trace("WebSocket.onMessage");
        webSocketProcessor.invokeWebSocketProtocol(message.toString());
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(message.toString(), MESSAGE, webSocketProcessor.webSocket()));
    }
}
