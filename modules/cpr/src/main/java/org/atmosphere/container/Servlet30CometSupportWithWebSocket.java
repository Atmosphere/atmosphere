/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.container;

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.websocket.JettyWebSocketHandler;
import org.atmosphere.websocket.WebSocketSupport;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
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
            String[] jettyVersion = config.getServletContext().getServerInfo().substring(6).split("\\.");
            if (Integer.valueOf(jettyVersion[0]) > 7 || Integer.valueOf(jettyVersion[0]) == 7 && Integer.valueOf(jettyVersion[1]) > 4) {
                webSocketFactory = new WebSocketFactory(new WebSocketFactory.Acceptor() {
                    public boolean checkOrigin(HttpServletRequest request, String origin) {
                        // Allow all origins
                        logger.debug("WebSocket-checkOrigin request {} with origin {}", request.getRequestURI(), origin);
                        return true;
                    }

                    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol) {
                        logger.debug("WebSocket-connect request {} with protocol {}", request.getRequestURI(), protocol);
                        return new JettyWebSocketHandler(request, config.getServlet(), config.getServlet().getWebSocketProcessorClassName());
                    }
                });
                webSocketFactory.setBufferSize(4096);
                webSocketFactory.setMaxIdleTime(60000);
            } else {
                webSocketFactory = null;
            }
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

        boolean webSocketEnabled = false;
        if (req.getHeaders("Connection") != null && req.getHeaders("Connection").hasMoreElements()) {
            String[] e = req.getHeaders("Connection").nextElement().split(",");
            for (String upgrade : e) {
                if (upgrade.trim().equalsIgnoreCase("Upgrade")) {
                    webSocketEnabled = true;
                    break;
                }
            }
        }

        if (!webSocketEnabled) {
            return super.service(req, res);
        } else {
            if (webSocketFactory != null && req.getAttribute("websocket") == null) {
                req.setAttribute("websocket", "inprocess");
                webSocketFactory.acceptWebSocket(req, res);
                req.setAttribute(WebSocketSupport.WEBSOCKET_SUSPEND, "true");
                return new Action();
            }

            Action action = suspended(req, res);
            if (action.type == Action.TYPE.SUSPEND) {
                logger.debug("Suspending response: {}", res);
            } else if (action.type == Action.TYPE.RESUME) {
                logger.debug("Resume response: {}", res);
                req.setAttribute(WebSocketSupport.WEBSOCKET_RESUME, "true");
            }

            return action;
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
