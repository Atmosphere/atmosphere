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

import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.websockets.DataFrame;
import com.sun.grizzly.websockets.DefaultWebSocket;
import com.sun.grizzly.websockets.WebSocketApplication;
import com.sun.grizzly.websockets.WebSocketEngine;
import org.atmosphere.container.version.GrizzlyWebSocket;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketAdapter;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;

/**
 * Websocket Portable Runtime implementation on top of GlassFish 3.0.1 and up.
 *
 * @author Jeanfrancois Arcand
 */
public class GlassFishWebSocketSupport extends GrizzlyCometSupport {

    private static final Logger logger = LoggerFactory.getLogger(GlassFishWebSocketSupport.class);

    public GlassFishWebSocketSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
        super.init(sc);
        WebSocketEngine.getEngine().register(new GrizzlyApplication(config));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Action service(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

        boolean webSocketEnabled = false;
        if (request.getHeaders("Connection") != null && request.getHeaders("Connection").hasMoreElements()) {
            String[] e = request.getHeaders("Connection").nextElement().split(",");
            for (String upgrade : e) {
                if (upgrade.equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                    webSocketEnabled = true;
                    break;
                }
            }
        }
        if (!webSocketEnabled) {
            return super.service(request, response);
        } else {
            Action action = suspended(request, response);
            if (action.type == Action.TYPE.SUSPEND) {
                logger.debug("Suspending response: {}", response);
            } else if (action.type == Action.TYPE.RESUME) {
                logger.debug("Resuming response: {}", response);
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

    /**
     * Grizzly support for WebSocket.
     */
    private final static class GrizzlyApplication extends WebSocketApplication {

        private final AtmosphereConfig config;

        private WebSocketProcessor webSocketProcessor;

        public GrizzlyApplication(AtmosphereConfig config) {
            this.config = config;
        }

        public void onConnect(com.sun.grizzly.websockets.WebSocket w) {
            super.onConnect(w);
            logger.debug("onOpen");
            if (!DefaultWebSocket.class.isAssignableFrom(w.getClass())) {
                throw new IllegalStateException();
            }

            WebSocketProtocol webSocketProtocol;
            try {
                webSocketProtocol = (WebSocketProtocol) GlassFishWebSocketSupport.class.getClassLoader()
                        .loadClass(config.getServlet().getWebSocketProtocolClassName()).newInstance();
            } catch (Exception ex) {
                logger.error("Cannot load the WebSocketProtocol {}", config.getServlet().getWebSocketProtocolClassName(), ex);
                webSocketProtocol = new SimpleHttpProtocol();
            }
            webSocketProtocol.configure(config.getServlet().getAtmosphereConfig());

            DefaultWebSocket webSocket = DefaultWebSocket.class.cast(w);
            try {
                webSocketProcessor = new WebSocketProcessor(config.getServlet(), new GrizzlyWebSocket(webSocket), webSocketProtocol);
                webSocketProcessor.dispatch(webSocket.getRequest());
            } catch (Exception e) {
                logger.warn("failed to connect to web socket", e);
            }
        }

        @Override
        public boolean isApplicationRequest(Request request) {
            return true;
        }

        @Override
        public void onClose(com.sun.grizzly.websockets.WebSocket w, DataFrame df) {
            super.onClose(w, df);
            logger.debug("onClose {} ");
            webSocketProcessor.close();
        }

        @Override
        public void onMessage(com.sun.grizzly.websockets.WebSocket w, String text) {
            logger.debug("onMessage {} ");
            webSocketProcessor.invokeWebSocketProtocol(text);
        }

        @Override
        public void onMessage(com.sun.grizzly.websockets.WebSocket w, byte[] bytes) {
            logger.debug("onMessage (bytes) {} ");
            webSocketProcessor.invokeWebSocketProtocol(bytes, 0, bytes.length);
        }

        @Override
        public void onPing(com.sun.grizzly.websockets.WebSocket w, byte[] bytes) {
            logger.debug("onPing (bytes) {} ");
        }

        @Override
        public void onPong(com.sun.grizzly.websockets.WebSocket w, byte[] bytes) {
            logger.debug("onPong (bytes) {} ");
        }

        @Override
        public void onFragment(com.sun.grizzly.websockets.WebSocket w, byte[] bytes, boolean last) {
            logger.debug("onFragment (bytes) {} ");
        }

        @Override
        public void onFragment(com.sun.grizzly.websockets.WebSocket w, String text, boolean last) {
            logger.debug("onFragment (string) {} ");
        }

    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }
}