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

import java.io.IOException;
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.websocket.WebSocket;
import org.jboss.servlet.http.HttpEvent;
import org.jboss.servlet.http.HttpEventServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JBoss's WebSocket support. This code has been adapted from
 * {@link org.atmosphere.container.Tomcat7AsyncSupportWithWebSocket} and
 * {@link org.atmosphere.container.TomcatWebSocketUtil}
 */
public class JBossAsyncSupportWithWebSocket extends JBossWebCometSupport {

    private static final Logger logger = LoggerFactory.getLogger(JBossAsyncSupportWithWebSocket.class);
    
    private final HttpEventServlet websocketHandler;

    public JBossAsyncSupportWithWebSocket(AtmosphereConfig config) {
        super(config);
        
        this.websocketHandler = newWebSocketHandler(config);
    }

    private HttpEventServlet newWebSocketHandler(AtmosphereConfig config) {
        try {
            return new JBossWebSocketHandler(config);
        } catch (Exception e) {
            logger.error("Cannot instantiate JBossWebSocketHandler. Websocket events will not be handled.", e);
        }
        
        return null;
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
        
        boolean allowWebSocketWithoutHeaders = req.getHeader(HeaderConfig.X_ATMO_WEBSOCKET_PROXY) != null ? true : false;
        if (!allowWebSocketWithoutHeaders)   {
            if (!headerContainsToken(req, "Upgrade", "websocket")) {
                return doService(req, res);
            }

            if (!headerContainsToken(req, "Connection", "upgrade")) {
                return doService(req, res);
            }

            if (!headerContainsToken(req, "sec-websocket-version", "13")) {
                WebSocket.notSupported(req, res);
                return new Action(Action.TYPE.CANCELLED);
            }
        }        
        
        try {
            Action action = suspended(req, res);
            if (action.type() == Action.TYPE.RESUME) {
                req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
            }
            return action;
        } catch (Exception ex) {
            logger.error("", ex);
        }
        return Action.CANCELLED;

    }

    public Action doService(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
        return super.service(req, res);
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }
    
    /**
     * @param httpEvent
     * @throws javax.servlet.ServletException
     * @throws java.io.IOException
     */
    public void dispatch(HttpEvent httpEvent) throws IOException, ServletException {
        if (websocketHandler != null) {
            websocketHandler.event(httpEvent);
        }
    }
    
    /*
    * This only works for tokens. Quoted strings need more sophisticated
    * parsing.
    */
    private static boolean headerContainsToken(HttpServletRequest req,
                                               String headerName, String target) {
        Enumeration<String> headers = req.getHeaders(headerName);
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            String[] tokens = header.split(",");
            for (String token : tokens) {
                if (target.equalsIgnoreCase(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

}

