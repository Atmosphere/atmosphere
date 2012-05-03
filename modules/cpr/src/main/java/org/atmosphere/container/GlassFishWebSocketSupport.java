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
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocketProcessor;
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
    private GrizzlyApplication application;

    public GlassFishWebSocketSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
        super.init(sc);
        application = new GrizzlyApplication(config);
        WebSocketEngine.getEngine().register(application);
    }

    @Override
    public void shutdown() {
        WebSocketEngine.getEngine().unregister(application);
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
            Action action = suspended(request, response);
            if (action.type() == Action.TYPE.SUSPEND) {
                logger.debug("Suspending response: {}", response);
            } else if (action.type() == Action.TYPE.RESUME) {
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
            //logger.debug("onOpen");
            if (!DefaultWebSocket.class.isAssignableFrom(w.getClass())) {
                throw new IllegalStateException();
            }

            DefaultWebSocket webSocket = DefaultWebSocket.class.cast(w);
            try {

                AtmosphereRequest r = AtmosphereRequest.wrap(webSocket.getRequest());
                try {
                    // GlassFish http://java.net/jira/browse/GLASSFISH-18681
                    if (r.getPathInfo().startsWith(r.getContextPath())) {
                        r.servletPath(r.getPathInfo().substring(r.getContextPath().length()));
                        r.pathInfo(null);
                    }
                } catch (Exception e) {
                    // Whatever exception occurs skip it
                    logger.trace("", e);
                }

                webSocketProcessor = new WebSocketProcessor(config.framework(), new GrizzlyWebSocket(webSocket), config.framework().getWebSocketProtocol());
                webSocketProcessor.dispatch(r);
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
            logger.trace("onClose {} ", w);
            // TODO: Need to talk to Ryan about that one.
            webSocketProcessor.close(1000);
        }

        @Override
        public void onMessage(com.sun.grizzly.websockets.WebSocket w, String text) {
            logger.trace("onMessage {} ", w);
            webSocketProcessor.invokeWebSocketProtocol(text);
        }

        @Override
        public void onMessage(com.sun.grizzly.websockets.WebSocket w, byte[] bytes) {
            logger.trace("onMessage (bytes) {} ", w);
            webSocketProcessor.invokeWebSocketProtocol(bytes, 0, bytes.length);
        }

        @Override
        public void onPing(com.sun.grizzly.websockets.WebSocket w, byte[] bytes) {
            logger.trace("onPing (bytes) {} ", w);
        }

        @Override
        public void onPong(com.sun.grizzly.websockets.WebSocket w, byte[] bytes) {
            logger.trace("onPong (bytes) {} ", w);
        }

        @Override
        public void onFragment(com.sun.grizzly.websockets.WebSocket w, byte[] bytes, boolean last) {
            logger.trace("onFragment (bytes) {} ", w);
        }

        @Override
        public void onFragment(com.sun.grizzly.websockets.WebSocket w, String text, boolean last) {
            logger.trace("onFragment (string) {} ", w);
        }

    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }
}