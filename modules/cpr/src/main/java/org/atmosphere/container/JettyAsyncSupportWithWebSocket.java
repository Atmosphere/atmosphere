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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * WebSocket Portable Runtime implementation on top of Jetty's.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyAsyncSupportWithWebSocket extends Jetty7CometSupport {

    private static final Logger logger = LoggerFactory.getLogger(JettyAsyncSupportWithWebSocket.class);
    private final WebSocketFactory webSocketFactory;

    public JettyAsyncSupportWithWebSocket(final AtmosphereConfig config) {
        super(config);
        final WebSocketProcessor webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config.framework());

        WebSocketFactory wsf;
        try {
            String[] jettyVersion = config.getServletContext().getServerInfo().substring(6).split("\\.");
            if (Integer.valueOf(jettyVersion[0]) > 7 || Integer.valueOf(jettyVersion[0]) == 7 && Integer.valueOf(jettyVersion[1]) > 4) {
                wsf = JettyWebSocketUtil.getFactory(config, webSocketProcessor);
            } else {
                wsf = null;
            }
        } catch (Throwable e) {
            // If we can't parse Jetty version, assume it's 8 and up.
            try {
                logger.trace("Unable to parse Jetty version {}", config.getServletContext().getServerInfo());
            } catch (Throwable t) {}
            wsf = JettyWebSocketUtil.getFactory(config, webSocketProcessor);
        }
        webSocketFactory = wsf;
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {
        Action action = JettyWebSocketUtil.doService(this,req,res,webSocketFactory);
        return action == null ? super.service(req,res) : action;
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