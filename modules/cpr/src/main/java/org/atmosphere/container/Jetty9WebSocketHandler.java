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

import org.atmosphere.container.version.Jetty9WebSocket;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;

public class Jetty9WebSocketHandler implements WebSocketListener {

    private static final Logger logger = LoggerFactory.getLogger(Jetty9WebSocketHandler.class);

    private AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private final WebSocketProcessor webSocketProcessor;
    private WebSocket webSocket;
    private static final boolean jetty93Up;

    static {
        Exception ex = null;
        try {
            Class.forName("org.eclipse.jetty.http2.server.HTTP2ServerConnection");
        } catch (ClassNotFoundException e) {
            ex = e;
        } finally {
            jetty93Up = ex == null ? false : true;
        }
    }

    public Jetty9WebSocketHandler(HttpServletRequest request, AtmosphereFramework framework, WebSocketProcessor webSocketProcessor) {
        this.framework = framework;
        this.request = cloneRequest(request);
        this.webSocketProcessor = webSocketProcessor;
    }

    private AtmosphereRequest cloneRequest(final HttpServletRequest request) {
        try {
            AtmosphereRequest r = AtmosphereRequest.wrap(request);
            return AtmosphereRequest.cloneRequest(r, false, false, false);
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

        /**
         * https://github.com/Atmosphere/atmosphere/issues/1998
         * The Original Jetty Request will be recycled, hence we must loads its content in memory. We can't do that before
         * as it break Jetty 9.3.0 upgrade process.
         *
         * This is a performance regression from 9.2 as we need to clone again the request. 9.3.0+ should use jsr 356!
         */
        if (jetty93Up) {
            HttpServletRequest r = originalRequest(session);
            if (r != null) {
                // We close except the session which we can still reach.
                request = AtmosphereRequest.cloneRequest(r, true, false, false, framework.getAtmosphereConfig().getInitParameter(PROPERTY_SESSION_CREATE, true));
            } else {
                // Bad Bad Bad
                request = AtmosphereRequest.cloneRequest(r, true, true, false, framework.getAtmosphereConfig().getInitParameter(PROPERTY_SESSION_CREATE, true));
            }
        }

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

    private HttpServletRequest originalRequest(Session session) {
        try {
            // Oh boy...
            ServletUpgradeRequest request = (ServletUpgradeRequest) session.getUpgradeRequest();
            Field[] fields = ServletUpgradeRequest.class.getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                Object o = f.get(request);
                if (o instanceof HttpServletRequest) {
                    return HttpServletRequest.class.cast(o);
                }
            }
        } catch (Exception ex) {
            logger.error("", ex);
        }
        return null;
    }

}
