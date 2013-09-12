/*
 * Copyright 2013 Jeanfrancois Arcand
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


import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class JettyWebSocketUtil {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocketUtil.class);

    public final static Action doService(AsynchronousProcessor cometSupport,
                                         AtmosphereRequest req,
                                         AtmosphereResponse res,
                                         WebSocketFactory webSocketFactory) throws IOException, ServletException {

        Boolean b = (Boolean) req.getAttribute(WebSocket.WEBSOCKET_INITIATED);
        if (b == null) b = Boolean.FALSE;

        if (!Utils.webSocketEnabled(req) && req.getAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE) == null) {
            if (req.resource() != null && req.resource().transport() == AtmosphereResource.TRANSPORT.WEBSOCKET) {
                WebSocket.notSupported(req, res);
                return Action.CANCELLED;
            } else {
                return null;
            }
        } else {
            if (webSocketFactory != null && !b) {
                req.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
                try {
                    webSocketFactory.acceptWebSocket(req, res);
                } catch (IllegalStateException ex) {
                    logger.trace("", ex);
                    WebSocket.notSupported(req, res);
                    return Action.CANCELLED;
                }
                req.setAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE, true);
                return new Action();
            }

            Action action = cometSupport.suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
            } else if (action.type() == Action.TYPE.RESUME) {
                req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
            }

            return action;
        }
    }

    public final static WebSocketFactory getFactory(final AtmosphereConfig config, final WebSocketProcessor webSocketProcessor) {

        final AtomicBoolean useBuildInSession = new AtomicBoolean(false);
        // Override the value.
        String s = config.getInitParameter(ApplicationConfig.BUILT_IN_SESSION);
        if (s != null) {
            useBuildInSession.set(Boolean.valueOf(s));
        }

        WebSocketFactory webSocketFactory = new WebSocketFactory(new WebSocketFactory.Acceptor() {
            public boolean checkOrigin(HttpServletRequest request, String origin) {
                // Allow all origins
                logger.trace("WebSocket-checkOrigin request {} with origin {}", request.getRequestURI(), origin);
                return true;
            }

            public org.eclipse.jetty.websocket.WebSocket doWebSocketConnect(HttpServletRequest request, String protocol) {
                logger.trace("WebSocket-connect request {} with protocol {}", request.getRequestURI(), protocol);

                boolean isDestroyable = false;
                String s = config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
                if (s != null && Boolean.valueOf(s)) {
                    isDestroyable = true;
                }

                if (!webSocketProcessor.handshake(request)) {
                    // res.sendError(HttpServletResponse.SC_FORBIDDEN, "WebSocket requests rejected.");
                    throw new IllegalStateException();
                }

                return new JettyWebSocketHandler(AtmosphereRequest.cloneRequest(request, false, useBuildInSession.get(), isDestroyable),
                        config.framework(), webSocketProcessor);
            }
        });

        int bufferSize = 8192;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE) != null) {
            bufferSize = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE));
        }
        logger.debug("WebSocket Buffer size {}", bufferSize);
        webSocketFactory.setBufferSize(bufferSize);

        int timeOut = 5 * 60000;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME) != null) {
            timeOut = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME));
        }
        logger.debug("WebSocket idle timeout {}", timeOut);
        webSocketFactory.setMaxIdleTime(timeOut);

        int maxTextBufferSize = 8192;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE) != null) {
            maxTextBufferSize = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE));
        }
        logger.debug("WebSocket maxTextBufferSize {}", maxTextBufferSize);
        webSocketFactory.setMaxTextMessageSize(maxTextBufferSize);

        int maxBinaryBufferSize = 8192;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXBINARYSIZE) != null) {
            maxBinaryBufferSize = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXBINARYSIZE));
        }
        logger.debug("WebSocket maxBinaryBufferSize {}", maxBinaryBufferSize);
        webSocketFactory.setMaxBinaryMessageSize(maxBinaryBufferSize);

        return webSocketFactory;
    }
}
