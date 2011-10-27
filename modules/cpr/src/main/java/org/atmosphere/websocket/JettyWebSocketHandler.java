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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.websocket.container.Jetty8WebSocket;
import org.atmosphere.websocket.container.JettyWebSocket;
import org.atmosphere.websocket.protocol.EchoProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import java.io.UnsupportedEncodingException;

import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.*;

/**
 * Jetty 7 & 8 WebSocket support.
 */
public class JettyWebSocketHandler implements org.eclipse.jetty.websocket.WebSocket, org.eclipse.jetty.websocket.WebSocket.OnFrame, org.eclipse.jetty.websocket.WebSocket.OnBinaryMessage, org.eclipse.jetty.websocket.WebSocket.OnTextMessage, org.eclipse.jetty.websocket.WebSocket.OnControl {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocketHandler.class);

    private WebSocketProcessor webSocketProcessor;
    private final HttpServletRequest request;
    private final AtmosphereServlet atmosphereServlet;
    private WebSocketProtocol webSocketProtocol;

    public JettyWebSocketHandler(HttpServletRequest request, AtmosphereServlet atmosphereServlet, final String webSocketProtocolClassName) {
        this.request = new JettyRequestFix(request, request.getServletPath(), request.getContextPath(), request.getPathInfo(), request.getRequestURI());
        this.atmosphereServlet = atmosphereServlet;

        try {
            this.webSocketProtocol = (WebSocketProtocol) JettyWebSocketHandler.class.getClassLoader()
                    .loadClass(webSocketProtocolClassName).newInstance();
            webSocketProtocol.configure(atmosphereServlet.getAtmosphereConfig());
        } catch (Exception ex) {
            logger.error("Cannot load the WebSocketProtocol {}", webSocketProtocolClassName, ex);
        }
    }

    @Override
    public void onConnect(org.eclipse.jetty.websocket.WebSocket.Outbound outbound) {
        logger.debug("WebSocket.onConnect (outbound)");
        try {
            webSocketProcessor = new WebSocketProcessor(atmosphereServlet, new JettyWebSocket(outbound), webSocketProtocol);
            webSocketProcessor.dispatch(request);
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onMessage(byte frame, String data) {
        logger.trace("WebSocket.onMessage (frame/string)");
        webSocketProcessor.invokeWebSocketProtocol(data);
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(data, MESSAGE, webSocketProcessor.webSocket()));
    }

    @Override
    public void onMessage(byte frame, byte[] data, int offset, int length) {
        logger.trace("WebSocket.onMessage (frame)");
        webSocketProcessor.invokeWebSocketProtocol(new String(data, offset, length));
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocket()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length) {
        logger.trace("WebSocket.onFragment");
        webSocketProcessor.invokeWebSocketProtocol(new String(data, offset, length));
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocket()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public void onDisconnect() {
        logger.trace("WebSocket.onDisconnect");
        webSocketProcessor.close();
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", DISCONNECT, webSocketProcessor.webSocket()));
    }

    @Override
    public void onMessage(byte[] data, int offset, int length) {
        logger.trace("WebSocket.onMessage (bytes)");
        webSocketProcessor.invokeWebSocketProtocol(data, offset, length);
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocket()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public boolean onControl(byte controlCode, byte[] data, int offset, int length) {
        logger.trace("WebSocket.onControl.");
        webSocketProcessor.invokeWebSocketProtocol(data, offset, length);
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), CONTROL, webSocketProcessor.webSocket()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
        return false;
    }

    @Override
    public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length) {
        logger.trace("WebSocket.onFrame.");
        // TODO: onMessage is always invoked after that method gets called, so no need to enable for now.
        //       webSocketProcessor.broadcast(data, offset, length);
        /* try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocket()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }*/
        return false;
    }

    @Override
    public void onHandshake(org.eclipse.jetty.websocket.WebSocket.FrameConnection connection) {
        logger.trace("WebSocket.onHandshake");
        try {
            webSocketProcessor = new WebSocketProcessor(atmosphereServlet, new Jetty8WebSocket(connection), webSocketProtocol);
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }

        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", HANDSHAKE, webSocketProcessor.webSocket()));
    }

    @Override
    public void onMessage(String data) {
        logger.trace("WebSocket.onMessage");
        webSocketProcessor.invokeWebSocketProtocol(data);
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(data, MESSAGE, webSocketProcessor.webSocket()));
    }

    @Override
    public void onOpen(org.eclipse.jetty.websocket.WebSocket.Connection connection) {
        logger.trace("WebSocket.onOpen.");
        try {
            webSocketProcessor = new WebSocketProcessor(atmosphereServlet, new Jetty8WebSocket(connection), webSocketProtocol);

            webSocketProcessor.dispatch(request);
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CONNECT, webSocketProcessor.webSocket()));
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onClose(int closeCode, String message) {
        logger.trace("WebSocket.OnClose.");
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CLOSE, webSocketProcessor.webSocket()));
        AtmosphereResource<?, ?> r = (AtmosphereResource<?, ?>) request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
        if (r != null) {
            r.getBroadcaster().removeAtmosphereResource(r);
        }
        webSocketProcessor.close();
    }

    /**
     * https://issues.apache.org/jira/browse/WICKET-3190
     */
    private static class JettyRequestFix extends HttpServletRequestWrapper {
        private final String contextPath;
        private final String servletPath;
        private final String pathInfo;
        private final String requestUri;

        public JettyRequestFix(HttpServletRequest request, String servletPath, String contextPath, String pathInfo, String requestUri) {
            super(request);
            this.servletPath = servletPath;
            this.contextPath = contextPath;
            this.pathInfo = pathInfo;
            this.requestUri = requestUri;
        }

        @Override
        public String getContextPath() {
            return contextPath;
        }

        @Override
        public String getServletPath() {
            return servletPath;
        }

        @Override
        public String getPathInfo() {
            return pathInfo;
        }

        @Override
        public String getRequestURI() {
            return requestUri;
        }
    }

}
