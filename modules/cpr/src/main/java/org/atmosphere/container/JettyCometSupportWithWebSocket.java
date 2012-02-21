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

import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * WebSocket Portable Runtime implementation on top of Jetty's.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyCometSupportWithWebSocket extends Jetty7CometSupport {

    private static final Logger logger = LoggerFactory.getLogger(JettyCometSupportWithWebSocket.class);
    private final WebSocketFactory webSocketFactory;

    public JettyCometSupportWithWebSocket(final AtmosphereConfig config) {
        super(config);

        WebSocketFactory wsf;
        try {
            String[] jettyVersion = config.getServletContext().getServerInfo().substring(6).split("\\.");
            if (Integer.valueOf(jettyVersion[0]) > 7 || Integer.valueOf(jettyVersion[0]) == 7 && Integer.valueOf(jettyVersion[1]) > 4) {
                wsf = JettyWebSocketUtil.getFactory(config);
            } else {
                wsf = null;
            }
        } catch (Throwable e) {
            // If we can't parse Jetty version, assume it's 8 and up.
            try {
                logger.trace("Unable to parse Jetty version {}", config.getServletContext().getServerInfo());
            } catch (Throwable t) {}
            wsf = JettyWebSocketUtil.getFactory(config);
        }
        webSocketFactory = wsf;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        Action action = JettyWebSocketUtil.doService(this,req,res,webSocketFactory);
        return action == null? super.service(req,res) : action;
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
    
    public WebSocketFactory getWebSocketFactory(){
    	return webSocketFactory;
    }
}