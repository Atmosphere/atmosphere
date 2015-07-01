/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.container.version.WebLogicWebSocket;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weblogic.websocket.ClosingMessage;
import weblogic.websocket.WSHandshakeRequest;
import weblogic.websocket.WSHandshakeResponse;
import weblogic.websocket.WebSocketConnection;
import weblogic.websocket.WebSocketContext;
import weblogic.websocket.WebSocketListener;

import javax.servlet.ServletContext;
import java.io.IOException;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SESSION_CREATE;

@weblogic.websocket.annotation.WebSocket(pathPatterns = "/ws/*", timeout = -1, maxMessageSize = 8192)
public class WeblogicWebSocketHandler implements WebSocketListener {

    private final Logger logger = LoggerFactory.getLogger(WeblogicWebSocketHandler.class);
    private WebSocketProcessor webSocketProcessor;
    private AtmosphereConfig config;
    private Integer maxTextBufferSize;
    private int webSocketWriteTimeout;
    private final ThreadLocal<WSHandshakeRequest> request = new ThreadLocal<WSHandshakeRequest>();

    @Override
    public void init(WebSocketContext webSocketContext) {
        logger.info("WebSocketContext initialized {}", webSocketContext);
        configure(webSocketContext.getServletContext());
    }

    @Override
    public void destroy() {

    }

    @Override
    public boolean accept(WSHandshakeRequest wsHandshakeRequest, WSHandshakeResponse wsHandshakeResponse) {
        request.set(wsHandshakeRequest);
        return true;
    }

    @Override
    public void onOpen(WebSocketConnection webSocketConnection) {
        if (webSocketWriteTimeout != -1)
            webSocketConnection.getWebSocketContext().setTimeoutSecs(webSocketWriteTimeout);
        if (maxTextBufferSize != -1) webSocketConnection.getWebSocketContext().setMaxMessageSize(maxTextBufferSize);

        WebSocket webSocket = new WebLogicWebSocket(webSocketConnection, config);
        // TODO: Dangerous
        webSocketConnection.getWebSocketContext().getServletContext().setAttribute(webSocketConnection.toString(), webSocket);

        AtmosphereRequest ar = AtmosphereRequestImpl.cloneRequest(request.get(), true, true, true, config.getInitParameter(PROPERTY_SESSION_CREATE, true));
        // https://github.com/Atmosphere/atmosphere/issues/1854
        // We need to force processing of the query string.
        ar.queryString(ar.getQueryString());
        request.set(null);
        try {
            webSocketProcessor.open(webSocket, ar, AtmosphereResponseImpl.newInstance(config, ar, webSocket));
        } catch (IOException e) {
            logger.error("{}", e);
        }

    }

    @Override
    public void onMessage(WebSocketConnection webSocketConnection, String s) {
        WebSocket webSocket = (WebSocket) webSocketConnection.getWebSocketContext().getServletContext().getAttribute(webSocketConnection.toString());

        webSocketProcessor.invokeWebSocketProtocol(webSocket, s);
    }

    @Override
    public void onMessage(WebSocketConnection webSocketConnection, byte[] bytes) {
        WebSocket webSocket = (WebSocket) webSocketConnection.getWebSocketContext().getServletContext().getAttribute(webSocketConnection.toString());

        webSocketProcessor.invokeWebSocketProtocol(webSocket, bytes, 0, bytes.length);
    }

    @Override
    public void onFragment(WebSocketConnection webSocketConnection, boolean b, String s) {
        logger.trace("Warning, Fragment not supported");
        onMessage(webSocketConnection, s);
    }

    @Override
    public void onFragment(WebSocketConnection webSocketConnection, boolean b, byte[] bytes) {
        logger.trace("Warning, Fragment not supported");
        onMessage(webSocketConnection, bytes);
    }

    @Override
    public void onPing(WebSocketConnection webSocketConnection, byte[] bytes) {
        logger.trace("Warning, Fragment not supported");
    }

    @Override
    public void onPong(WebSocketConnection webSocketConnection, byte[] bytes) {
        logger.trace("Warning, Fragment not supported");
    }

    @Override
    public void onTimeout(WebSocketConnection webSocketConnection) {
        WebSocket webSocket = (WebSocket) webSocketConnection.getWebSocketContext().getServletContext().getAttribute(webSocketConnection.toString());

        webSocketProcessor.close(webSocket, 1000);
    }

    @Override
    public void onError(WebSocketConnection webSocketConnection, Throwable throwable) {
        WebSocket webSocket = (WebSocket) webSocketConnection.getWebSocketContext().getServletContext().getAttribute(webSocketConnection.toString());
        webSocketConnection.getWebSocketContext().getServletContext().removeAttribute(webSocketConnection.toString());

        webSocketProcessor.notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent<Throwable>(throwable, WebSocketEventListener.WebSocketEvent.TYPE.EXCEPTION, webSocket));
    }

    @Override
    public void onClose(WebSocketConnection webSocketConnection, ClosingMessage closingMessage) {
        WebSocket webSocket = (WebSocket) webSocketConnection.getWebSocketContext().getServletContext().getAttribute(webSocketConnection.toString());
        webSocketConnection.getWebSocketContext().getServletContext().removeAttribute(webSocketConnection.toString());

        webSocketProcessor.close(webSocket, closingMessage.getStatusCode());
    }

    private void configure(ServletContext servletContext) {
        config = (AtmosphereConfig) servletContext.getAttribute(AtmosphereConfig.class.getName());
        webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config.framework());

        String s = config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME);
        if (s != null) {
            webSocketWriteTimeout = Integer.valueOf(s);
        } else {
            webSocketWriteTimeout = -1;
        }

        s = config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE);
        if (s != null) {
            maxTextBufferSize = Integer.valueOf(s);
        } else {
            maxTextBufferSize = -1;
        }
    }
}

