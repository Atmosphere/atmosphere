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

import com.sun.grizzly.websockets.WebSocketEngine;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Websocket Portable Runtime implementation on top of GlassFish 3.0.1 and up.
 *
 * @author Jeanfrancois Arcand
 */
public class GlassFishWebSocketSupport extends GrizzlyCometSupport {

    private static final Logger logger = LoggerFactory.getLogger(GlassFishWebSocketSupport.class);
    private GlassFishWebSocketHandler glassfishWebSocketHandler;

    public GlassFishWebSocketSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
        super.init(sc);
        glassfishWebSocketHandler = new GlassFishWebSocketHandler(config);
        WebSocketEngine.getEngine().register(glassfishWebSocketHandler);
    }

    @Override
    public void shutdown() {
        WebSocketEngine.getEngine().unregister(glassfishWebSocketHandler);
        super.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Action service(AtmosphereRequest request, AtmosphereResponse response)
            throws IOException, ServletException {

        if (!Utils.webSocketEnabled(request)) {
            return super.service(request, response);
        } else {
            boolean webSocketNotSupported = request.getAttribute(WebSocket.WEBSOCKET_SUSPEND) == null;

            if (webSocketNotSupported)  {
                WebSocket.notSupported(request, response);
                return Action.CANCELLED;
            }

            return suspended(request, response);
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
}