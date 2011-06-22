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

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.WebSocketProcessor;
import org.eclipse.jetty.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;

/**
 * Jetty 7 & 8 WebSocket support.
 */
public class JettyWebSocketListener implements WebSocket, WebSocket.OnFrame, WebSocket.OnBinaryMessage, WebSocket.OnTextMessage, WebSocket.OnControl {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocketListener.class);

    private WebSocketProcessor webSocketProcessor;
    private final HttpServletRequest request;
    private final AtmosphereServlet atmosphereServlet;
    private Connection connection;

    public JettyWebSocketListener(HttpServletRequest request, AtmosphereServlet atmosphereServlet) {
        this.request = request;
        this.atmosphereServlet = atmosphereServlet;
    }

    @Override
    public void onConnect(WebSocket.Outbound outbound) {
        webSocketProcessor = new WebSocketProcessor(atmosphereServlet, new JettyWebSocketSupport(outbound));
        try {
            webSocketProcessor.connect(new JettyRequestFix(request));
        } catch (IOException e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onMessage(byte frame, String data) {
        webSocketProcessor.broadcast(frame, data);
    }

    @Override
    public void onMessage(byte frame, byte[] data, int offset, int length) {
        webSocketProcessor.broadcast(frame, new String(data, offset, length));
    }

    @Override
    public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length) {
        webSocketProcessor.broadcast(opcode, new String(data, offset, length));
    }

    @Override
    public void onDisconnect() {
        webSocketProcessor.close();
    }

    @Override
    public void onMessage(byte[] data, int offset, int length) {
        try {
            connection.sendMessage(data, offset, length);
        } catch (IOException e) {
            logger.warn("WebSocket.onMessage", e);
        }
    }

    @Override
    public boolean onControl(byte controlCode, byte[] data, int offset, int length) {
        try {
            connection.sendMessage(data, offset, length);
        } catch (IOException e) {
            logger.warn("WebSocket.onMessage", e);
        }
        return false;
    }

    @Override
    public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length) {
        try {
            connection.sendMessage(data, offset, length);
        } catch (IOException e) {
            logger.warn("WebSocket.onFrame", e);
        }
        logger.debug("WebSocket.onFrame");
        return false;
    }

    @Override
    public void onHandshake(WebSocket.FrameConnection connection) {
        logger.debug("WebSocket.onHandshake");
    }

    @Override
    public void onMessage(String data) {
        try {
            connection.sendMessage(data);
        } catch (IOException e) {
            logger.warn("WebSocket.onMessage", e);
        }
    }

    @Override
    public void onOpen(WebSocket.Connection connection) {
        this.connection = connection;
        logger.debug("WebSocket.onOpen");
    }

    @Override
    public void onClose(int closeCode, String message) {
        connection.disconnect();
        logger.debug("WebSocket.onClose {}", message);

    }

    /**
     * https://issues.apache.org/jira/browse/WICKET-3190
     */
    private static class JettyRequestFix extends HttpServletRequestWrapper {

        public JettyRequestFix(HttpServletRequest request) {
            super(request);
        }

        /**
         * Jetty's Websocket doesn't computer the ContextPath properly for WebSocket.
         *
         * @return
         */
        public String getContextPath() {
            String uri = getRequestURI();
            String path = super.getContextPath();
            if (path == null) {
                path = uri.substring(0, uri.indexOf("/", 1));
            }
            return path;
        }
    }

}
