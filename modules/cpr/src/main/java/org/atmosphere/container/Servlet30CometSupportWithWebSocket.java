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
import org.atmosphere.config.AtmosphereConfig;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * This class is the same as {@link JettyCometSupportWithWebSocket} implementation and add Websocket support
 * to Servlet 3.0.
 *
 * @author Jeanfrancois Arcand
 */
public class Servlet30CometSupportWithWebSocket extends Servlet30CometSupport {

    private static final Logger logger = LoggerFactory.getLogger(Servlet30CometSupportWithWebSocket.class);
    private final WebSocketFactory webSocketFactory;

    public Servlet30CometSupportWithWebSocket(final AtmosphereConfig config) {
        super(config);

        boolean isJetty = config.getServletContext().getServerInfo().toLowerCase().startsWith("jetty");
        if (isJetty) {
            webSocketFactory = JettyWebSocketUtil.getFactory(config);
        }  else {
            webSocketFactory = null;
        }
        //TODO: Add Grizzly support here as well.

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
}
