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
package org.atmosphere.container;

import org.atmosphere.container.version.Jetty8WebSocket;
import org.atmosphere.container.version.JettyWebSocket;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.util.FakeHttpSession;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;

import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CLOSE;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CONNECT;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CONTROL;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.DISCONNECT;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.HANDSHAKE;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.MESSAGE;

/**
 * Jetty 7 & 8 WebSocket support.
 */
public class JettyWebSocketHandler implements org.eclipse.jetty.websocket.WebSocket,
        org.eclipse.jetty.websocket.WebSocket.OnFrame,
        org.eclipse.jetty.websocket.WebSocket.OnBinaryMessage,
        org.eclipse.jetty.websocket.WebSocket.OnTextMessage,
        org.eclipse.jetty.websocket.WebSocket.OnControl {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocketHandler.class);

    private WebSocketProcessor webSocketProcessor;
    private final JettyRequestFix request;
    private final AtmosphereFramework framework;
    private WebSocketProtocol webSocketProtocol;

    public JettyWebSocketHandler(HttpServletRequest request, AtmosphereFramework framework, WebSocketProtocol webSocketProtocol) {
        this.request = new JettyRequestFix(request);
        this.framework = framework;
        this.webSocketProtocol = webSocketProtocol;
    }

    @Override
    public void onConnect(org.eclipse.jetty.websocket.WebSocket.Outbound outbound) {

        logger.debug("WebSocket.onConnect (outbound)");
        try {
            webSocketProcessor = new WebSocketProcessor(framework, new JettyWebSocket(outbound), webSocketProtocol);
            webSocketProcessor.dispatch(AtmosphereRequest.wrap(request));
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
        request.destroy();
        logger.trace("WebSocket.onDisconnect");
        webSocketProcessor.close(1000);
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
            webSocketProcessor = new WebSocketProcessor(framework, new Jetty8WebSocket(connection, framework.getAtmosphereConfig()), webSocketProtocol);
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
            webSocketProcessor = new WebSocketProcessor(framework, new Jetty8WebSocket(connection, framework.getAtmosphereConfig()), webSocketProtocol);
            webSocketProcessor.dispatch(AtmosphereRequest.wrap(request));
            webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CONNECT, webSocketProcessor.webSocket()));
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onClose(int closeCode, String message) {
        request.destroy();
        if (webSocketProcessor == null) return;

        webSocketProcessor.notifyListener(new WebSocketEventListener.WebSocketEvent("", CLOSE, webSocketProcessor.webSocket()));
        webSocketProcessor.close(closeCode);

    }

    /**
     * Starting with 7.5.x and 8.0.2, the internal Jetty's Request object gets recycled once the handshake occurs,
     * hence we need to cache the original value. Since a WebSocketProtocol handler can wrap the request, we must first
     * do it here to avoid all kind of issue with Jetty.
     */
    private static class JettyRequestFix extends HttpServletRequestWrapper {
        private final String contextPath;
        private final String servletPath;
        private final String pathInfo;
        private final String requestUri;
        private final FakeHttpSession httpSession;
        private final StringBuffer requestURL;
        private final HashMap<String, Object> attributes = new HashMap<String, Object>();
        private final HashMap<String, String> headers = new HashMap<String, String>();
        private final HashMap<String, String[]> parameters = new HashMap<String, String[]>();
        private final String method;
        private final String serverName;
        private final int serverPort;

        public JettyRequestFix(HttpServletRequest request) {
            super(request);
            this.servletPath = request.getServletPath();
            this.contextPath = request.getContextPath();
            this.pathInfo = request.getPathInfo();
            this.requestUri = request.getRequestURI();
            this.requestURL = request.getRequestURL();
            this.method = request.getMethod();
            this.serverName = request.getServerName();
            this.serverPort = request.getServerPort();

            HttpSession session = request.getSession(true);
            httpSession = new FakeHttpSession(session.getId(), session.getServletContext(), session.getCreationTime());

            Enumeration<String> e = request.getHeaderNames();
            String s;
            while (e.hasMoreElements()) {
                s = e.nextElement();
                headers.put(s, request.getHeader(s));
            }

            e = request.getAttributeNames();
            while (e.hasMoreElements()) {
                s = e.nextElement();
                attributes.put(s, request.getAttribute(s));
            }

            e = request.getParameterNames();
            while (e.hasMoreElements()) {
                s = e.nextElement();
                parameters.put(s, request.getParameterValues(s));
            }
        }

        public void destroy() {
            attributes.clear();
            headers.clear();
            parameters.clear();
            httpSession.destroy();
        }

        @Override
        public String getServerName() {
            return serverName;
        }

        @Override
        public int getServerPort() {
            return serverPort;
        }

        @Override
        public HttpSession getSession(boolean create) {
            return httpSession;
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        public Enumeration<String> getHeaders(final String name) {
            return new Enumeration<String>() {

                boolean hasNext = true;

                @Override
                public boolean hasMoreElements() {
                    return hasNext && headers.get(name) != null;
                }

                @Override
                public String nextElement() {
                    hasNext = false;
                    return headers.get(name);
                }
            };
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String getParameter(String name) {
            return parameters.get(name) != null ? parameters.get(name)[0] : null;
        }

        @Override
        public String[] getParameterValues(final String name) {
            return parameters.get(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.enumeration(attributes.keySet());
        }

        @Override
        public void setAttribute(String name, Object o) {
            attributes.put(name, o);
        }

        @Override
        public void removeAttribute(String name) {
            attributes.remove(name);
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

        @Override
        public HttpSession getSession() {
            return httpSession;
        }

        @Override
        public StringBuffer getRequestURL() {
            return requestURL;
        }
    }

}
