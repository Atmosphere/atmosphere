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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.ServletWebSocketRequest;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * Jetty 9 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty9AsyncSupportWithWebSocket extends Servlet30CometSupport {
    private static final Logger logger = LoggerFactory.getLogger(Jetty9AsyncSupportWithWebSocket.class);
    private final WebSocketServerFactory webSocketFactory;

    public Jetty9AsyncSupportWithWebSocket(final AtmosphereConfig config) {
        super(config);

        String bs = config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE);
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        if (bs != null) {
            policy.setInputBufferSize(Integer.parseInt(bs));
        }

        String max = config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME);
        if (max != null) {
            policy.setIdleTimeout(Integer.parseInt(max));
        }

        max = config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE);
        if (max != null) {
            policy.setMaxMessageSize(Integer.parseInt(max));
        }

        max = config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXBINARYSIZE);
        if (max != null) {
            policy.setMaxMessageSize(Integer.parseInt(max));
        }
        final WebSocketProcessor webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config.framework());

        webSocketFactory = new WebSocketServerFactory(policy) {
            @Override
            public boolean acceptWebSocket(final HttpServletRequest request, HttpServletResponse response) throws IOException {
                setCreator(new WebSocketCreator() {

                    @Override
                    public Object createWebSocket(UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse) {

                        ServletWebSocketRequest r = ServletWebSocketRequest.class.cast(upgradeRequest);
                        r.getExtensions().clear();

                        return new Jetty9WebSocketHandler(request, config.framework(), webSocketProcessor);
                    }
                });

                return super.acceptWebSocket(request, response);
            }
        };

        try {
            webSocketFactory.start();
        } catch (Exception e) {
            logger.error("", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = null;
        Boolean b = (Boolean) req.getAttribute(WebSocket.WEBSOCKET_INITIATED);
        if (b == null) b = Boolean.FALSE;

        if (!Utils.webSocketEnabled(req) && req.getAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE) == null) {
            if (req.resource() != null && req.resource().transport() == AtmosphereResource.TRANSPORT.WEBSOCKET) {
                WebSocket.notSupported(req, res);
                return Action.CANCELLED;
            } else {
                return super.service(req, res);
            }
        } else {
            if (webSocketFactory != null && !b) {
                req.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
                webSocketFactory.acceptWebSocket(req, res);
                req.setAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE, true);
                return new Action();
            }

            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
            } else if (action.type() == Action.TYPE.RESUME) {
                req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
            }
        }

        return action == null ? super.service(req, res) : action;
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


}
