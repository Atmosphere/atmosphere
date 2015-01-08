/*
 * Copyright 2015 Sebastien Dionne
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

import org.atmosphere.container.version.Jetty9WebSocket;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SESSION_CREATE;

public class Jetty9WebSocketHandler implements WebSocketListener {

    private static final Logger logger = LoggerFactory.getLogger(Jetty9WebSocketHandler.class);

    private final AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private final WebSocketProcessor webSocketProcessor;
    private WebSocket webSocket;

    public Jetty9WebSocketHandler(HttpServletRequest request, AtmosphereFramework framework, WebSocketProcessor webSocketProcessor) {
        this.framework = framework;
        this.request = cloneRequest(request);
        this.webSocketProcessor = webSocketProcessor;
    }

    private AtmosphereRequest cloneRequest(final HttpServletRequest request) {
        try {
            AtmosphereRequest r = AtmosphereRequest.wrap(request);
            return AtmosphereRequest.cloneRequest(r, false, false, false, framework.getAtmosphereConfig().getInitParameter(PROPERTY_SESSION_CREATE, true));
        } catch (Exception ex) {
            logger.error("", ex);
            throw new RuntimeException("Invalid WebSocket Request");
        }
    }

    @Override
    public void onWebSocketBinary(byte[] data, int offset, int length) {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(webSocket, data, offset, length);
    }

    @Override
    public void onWebSocketClose(int closeCode, String s) {
        logger.trace("onClose {}:{}", closeCode, s);
        try {
            webSocketProcessor.close(webSocket, closeCode);
        } finally {
            request.destroy();
        }
    }

    @Override
    public void onWebSocketConnect(Session session) {
        logger.trace("WebSocket.onOpen.");
        webSocket = new Jetty9WebSocket(session, framework.getAtmosphereConfig());
        try {
            webSocketProcessor.open(webSocket, request, AtmosphereResponse.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        } catch (Exception e) {
            logger.warn("Failed to connect to WebSocket", e);
        }
    }

    @Override
    public void onWebSocketError(Throwable e) {
        logger.error("{}", e);
        onWebSocketClose(1006, "Unexpected error");
    }

    @Override
    public void onWebSocketText(String s) {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(webSocket, s);
    }
}
