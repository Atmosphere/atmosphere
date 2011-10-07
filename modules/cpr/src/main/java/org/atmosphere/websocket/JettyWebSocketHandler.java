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
    private final String webSocketProcessorClassName;

    public JettyWebSocketHandler(HttpServletRequest request, AtmosphereServlet atmosphereServlet, final String webSocketProcessorClassName) {
        this.request = new JettyRequestFix(request, request.getServletPath(), request.getContextPath(), request.getPathInfo(), request.getRequestURI());
        this.atmosphereServlet = atmosphereServlet;
        this.webSocketProcessorClassName = webSocketProcessorClassName;
    }

    @Override
    public void onConnect(org.eclipse.jetty.websocket.WebSocket.Outbound outbound) {
        logger.debug("WebSocket.onConnect (outbound)");
        try {
            webSocketProcessor = (WebSocketProcessor) JettyWebSocketHandler.class.getClassLoader()
                    .loadClass(webSocketProcessorClassName)
                    .getDeclaredConstructor(new Class[]{AtmosphereServlet.class, WebSocket.class})
                    .newInstance(new Object[]{atmosphereServlet, new JettyWebSocket(outbound)});

            webSocketProcessor.connect(request);
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onMessage(byte frame, String data) {
        logger.debug("WebSocket.onMessage (frame/string)");
        webSocketProcessor.broadcast(data);
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(data, MESSAGE, webSocketProcessor.webSocketSupport()));
    }

    @Override
    public void onMessage(byte frame, byte[] data, int offset, int length) {
        logger.debug("WebSocket.onMessage (frame)");
        webSocketProcessor.broadcast(new String(data, offset, length));
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocketSupport()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length) {
        logger.debug("WebSocket.onFragment");
        webSocketProcessor.broadcast(new String(data, offset, length));
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocketSupport()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public void onDisconnect() {
        logger.debug("WebSocket.onDisconnect");
        webSocketProcessor.close();
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", DISCONNECT, webSocketProcessor.webSocketSupport()));
    }

    @Override
    public void onMessage(byte[] data, int offset, int length) {
        logger.debug("WebSocket.onMessage (bytes)");
        webSocketProcessor.broadcast(data, offset, length);
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocketSupport()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
    }

    @Override
    public boolean onControl(byte controlCode, byte[] data, int offset, int length) {
        logger.debug("WebSocket.onControl.");
        webSocketProcessor.broadcast(data, offset, length);
        try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), CONTROL, webSocketProcessor.webSocketSupport()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }
        return false;
    }

    @Override
    public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length) {
        logger.debug("WebSocket.onFrame.");
        // TODO: onMessage is always invoked after that method gets called, so no need to enable for now.
        //       webSocketProcessor.broadcast(data, offset, length);
       /* try {
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(new String(data, offset, length, "UTF-8"), MESSAGE, webSocketProcessor.webSocketSupport()));
        } catch (UnsupportedEncodingException e) {
            logger.warn("UnsupportedEncodingException", e);

        }*/
        return false;
    }

    @Override
    public void onHandshake(org.eclipse.jetty.websocket.WebSocket.FrameConnection connection) {
        logger.debug("WebSocket.onHandshake");
        try {
            webSocketProcessor = (WebSocketProcessor) JettyWebSocketHandler.class.getClassLoader()
                    .loadClass(webSocketProcessorClassName)
                    .getDeclaredConstructor(new Class[]{AtmosphereServlet.class, WebSocket.class})
                    .newInstance(new Object[]{atmosphereServlet, new Jetty8WebSocket(connection)});
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }

        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", HANDSHAKE, webSocketProcessor.webSocketSupport()));
    }

    @Override
    public void onMessage(String data) {
        logger.debug("WebSocket.onMessage");
        webSocketProcessor.broadcast(data);
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent(data, MESSAGE, webSocketProcessor.webSocketSupport()));
    }

    @Override
    public void onOpen(org.eclipse.jetty.websocket.WebSocket.Connection connection) {
        logger.debug("WebSocket.onOpen.");
        try {
            webSocketProcessor = (WebSocketProcessor) JettyWebSocketHandler.class.getClassLoader()
                    .loadClass(webSocketProcessorClassName)
                    .getDeclaredConstructor(new Class[]{AtmosphereServlet.class, WebSocket.class})
                    .newInstance(new Object[]{atmosphereServlet, new Jetty8WebSocket(connection)});
            webSocketProcessor.connect(request);
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CONNECT, webSocketProcessor.webSocketSupport()));
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onClose(int closeCode, String message) {
        logger.debug("WebSocket.OnClose.");
        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CLOSE, webSocketProcessor.webSocketSupport()));
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
