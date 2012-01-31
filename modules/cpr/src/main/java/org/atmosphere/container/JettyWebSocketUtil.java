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


import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;

public class JettyWebSocketUtil {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocketUtil.class);

    public final static AtmosphereServlet.Action doService(AsynchronousProcessor cometSupport,
                                                           HttpServletRequest req,
                                                           HttpServletResponse res,
                                                           WebSocketFactory webSocketFactory) throws IOException, ServletException {
        boolean webSocketEnabled = false;
        if (req.getHeaders("Connection") != null && req.getHeaders("Connection").hasMoreElements()) {
            String[] e = req.getHeaders("Connection").nextElement().toString().split(",");
            for (String upgrade : e) {
                if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                    webSocketEnabled = true;
                    break;
                }
            }
        }

        Boolean b = (Boolean) req.getAttribute(WebSocket.WEBSOCKET_INITIATED);
        if (b == null) b = Boolean.FALSE;

        if (!webSocketEnabled) {
            return null;
        } else {
            if (webSocketFactory != null && !b) {
                req.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
                webSocketFactory.acceptWebSocket(req, res);
                return new AtmosphereServlet.Action();
            }

            AtmosphereServlet.Action action = cometSupport.suspended(req, res);
            if (action.type == AtmosphereServlet.Action.TYPE.SUSPEND) {
                logger.debug("Suspending response: {}", res);
            } else if (action.type == AtmosphereServlet.Action.TYPE.RESUME) {
                logger.debug("Resume response: {}", res);
                req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
            }

            return action;
        }
    }

    public final static WebSocketFactory getFactory(final AtmosphereConfig config) {
        WebSocketFactory webSocketFactory = new WebSocketFactory(new WebSocketFactory.Acceptor() {
            public boolean checkOrigin(HttpServletRequest request, String origin) {
                // Allow all origins
                logger.debug("WebSocket-checkOrigin request {} with origin {}", request.getRequestURI(), origin);
                return true;
            }

            public org.eclipse.jetty.websocket.WebSocket doWebSocketConnect(HttpServletRequest request, String protocol) {
                logger.debug("WebSocket-connect request {} with protocol {}", request.getRequestURI(), protocol);
                return new JettyWebSocketHandler(request, config.getAtmosphereServlet(), config.getAtmosphereServlet().getWebSocketProtocol());
            }
        });

        int bufferSize = 8192;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE) != null) {
            bufferSize = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE));
        }
        logger.info("WebSocket Buffer side {}", bufferSize);

        webSocketFactory.setBufferSize(bufferSize);
        int timeOut = 5 * 60000;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME) != null) {
            timeOut = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME));
        }
        logger.info("WebSocket idle timeout {}", timeOut);

        webSocketFactory.setMaxIdleTime(timeOut);
        return webSocketFactory;
    }
}
