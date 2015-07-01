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

import org.atmosphere.container.version.Grizzly2WebSocket;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.util.IOUtils;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocketProcessor;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.DefaultWebSocket;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.grizzly.websockets.WebSocketApplication;
import org.glassfish.grizzly.websockets.WebSocketEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

public class Grizzly2WebSocketSupport extends Grizzly2CometSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(Grizzly2WebSocketSupport.class);

    private Grizzly2WebSocketApplication application;


    // ------------------------------------------------------------ Constructors

    public Grizzly2WebSocketSupport(AtmosphereConfig config) {
        super(config);
        application = new Grizzly2WebSocketApplication(config);
        WebSocketEngine.getEngine().register(config.getServletContext().getContextPath(), IOUtils.guestRawServletPath(config), application);
    }


    // -------------------------------------- Methods from AsynchronousProcessor

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {
        if (Utils.webSocketEnabled(req)) {
            return suspended(req, res);
        } else {
            return super.service(req, res);
        }
    }

    /**
     * Return the container's name.
     */
    public String getContainerName() {
        return config.getServletConfig().getServletContext().getServerInfo() + " with WebSocket enabled.";
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        WebSocketEngine.getEngine().unregister(application);
        super.shutdown();
    }

    // ---------------------------------------------------------- Nested Classes


    private static final class Grizzly2WebSocketApplication extends WebSocketApplication {

        private AtmosphereConfig config;
        private final WebSocketProcessor webSocketProcessor;

        // -------------------------------------------------------- Constructors


        public Grizzly2WebSocketApplication(AtmosphereConfig config) {
            this.config = config;
            this.webSocketProcessor = WebSocketProcessorFactory.getDefault()
                    .getWebSocketProcessor(config.framework());
        }


        // --------------------------- Methods from Grizzly2WebSocketApplication

        @Override
        protected void handshake(org.glassfish.grizzly.websockets.HandShake handshake) throws org.glassfish.grizzly.websockets.HandshakeException{
            if (!webSocketProcessor.handshake(null)) {
                throw new org.glassfish.grizzly.websockets.HandshakeException("WebSocket not accepted");
            }
        }

        @Override
        public void onClose(WebSocket socket, DataFrame frame) {
            super.onClose(socket, frame);
            LOGGER.trace("onClose {} ", socket);
            DefaultWebSocket g2w = DefaultWebSocket.class.cast(socket);
            org.atmosphere.websocket.WebSocket webSocket = (org.atmosphere.websocket.WebSocket) g2w.getUpgradeRequest().getAttribute("grizzly.webSocket");
            if (webSocket != null) {
                webSocketProcessor.close(webSocket, 1000);
            }
        }

        @Override
        public void onConnect(WebSocket socket) {
            super.onConnect(socket);
            LOGGER.trace("onConnect {} ", socket);

            if (!DefaultWebSocket.class.isAssignableFrom(socket.getClass())) {
                throw new IllegalStateException();
            }

            DefaultWebSocket g2WebSocket = DefaultWebSocket.class.cast(socket);
            try {

                AtmosphereRequest r = AtmosphereRequestImpl.wrap(g2WebSocket.getUpgradeRequest());
                org.atmosphere.websocket.WebSocket webSocket = new Grizzly2WebSocket(g2WebSocket, config);
                g2WebSocket.getUpgradeRequest().setAttribute("grizzly.webSocket", webSocket);
                webSocketProcessor.open(webSocket, r, AtmosphereResponseImpl.newInstance(config, r, webSocket));
            } catch (Exception e) {
                LOGGER.warn("failed to connect to web socket", e);
            }
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            super.onMessage(socket, text);
            LOGGER.trace("onMessage(String) {} ", socket);
            DefaultWebSocket g2w = DefaultWebSocket.class.cast(socket);
            org.atmosphere.websocket.WebSocket webSocket = (org.atmosphere.websocket.WebSocket) g2w.getUpgradeRequest().getAttribute("grizzly.webSocket");
            if (webSocket != null) {
                webSocketProcessor.invokeWebSocketProtocol(webSocket, text);
            }
        }

        @Override
        public void onMessage(WebSocket socket, byte[] bytes) {
            super.onMessage(socket, bytes);
            LOGGER.trace("onMessage(byte[]) {} ", socket);
            DefaultWebSocket g2w = DefaultWebSocket.class.cast(socket);
            org.atmosphere.websocket.WebSocket webSocket = (org.atmosphere.websocket.WebSocket) g2w.getUpgradeRequest().getAttribute("grizzly.webSocket");
            if (webSocket != null) {
                webSocketProcessor.invokeWebSocketProtocol(webSocket, bytes, 0, bytes.length);
            }
        }

        @Override
        public void onPing(WebSocket socket, byte[] bytes) {
            LOGGER.trace("onPing {} ", socket);
        }

        @Override
        public void onPong(WebSocket socket, byte[] bytes) {
            LOGGER.trace("onPong {} ", socket);
        }

        @Override
        public void onFragment(WebSocket socket, String fragment, boolean last) {
            LOGGER.trace("onFragment(String) {} ", socket);
        }

        @Override
        public void onFragment(WebSocket socket, byte[] fragment, boolean last) {
            LOGGER.trace("onFragment(byte) {} ", socket);
        }

    } // END Grizzly2WebSocketApplication
}