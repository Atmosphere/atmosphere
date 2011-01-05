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
import com.sun.grizzly.websockets.BaseServerWebSocket;
import com.sun.grizzly.websockets.DataFrame;
import com.sun.grizzly.websockets.WebSocket;
import com.sun.grizzly.websockets.WebSocketApplication;
import com.sun.grizzly.websockets.WebSocketEngine;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.cpr.WebSocketProcessor;
import org.atmosphere.util.LoggerUtils;
import org.atmosphere.websocket.WebSocketSupport;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Websocket Portable Runtime implementation on top of GlassFish 3.0.1 and up.
 *
 * @author Jeanfrancois Arcand
 */
public class GlassFishWebSocketSupport extends GrizzlyCometSupport {

    public GlassFishWebSocketSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
        super.init(sc);
        WebSocketEngine.getEngine().register(sc.getServletContext().getContextPath(), new GrizzlyApplication());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Action service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        String connection = req.getHeader("Connection");
        if (!"Upgrade".equalsIgnoreCase(connection)) {
            return super.service(req, res);
        } else {
            Action action = suspended(req, res);
            if (action.type == Action.TYPE.SUSPEND) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Suspending" + res);
                }
            } else if (action.type == Action.TYPE.RESUME) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Resuming" + res);
                }
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
    public class GrizzlyApplication extends WebSocketApplication {

        private WebSocketProcessor webSocketProcessor;

        public void onConnect(WebSocket w) {

            if (!BaseServerWebSocket.class.isAssignableFrom(w.getClass())) {
                throw new IllegalStateException();
            }

            BaseServerWebSocket webSocket = BaseServerWebSocket.class.cast(w);

            webSocketProcessor = new WebSocketProcessor(config.getServlet(), new GrizzlyWebSocketSupport(webSocket));
            try {
                webSocketProcessor.connect(new HttpServletRequestWrapper(webSocket.getRequest()));
            } catch (IOException e) {
                LoggerUtils.getLogger().log(Level.WARNING, "", e);
            }
        }

        @Override
        public boolean isApplicationRequest(Request request) {
            return true;
        }

        public void onMessage(WebSocket webSocket, DataFrame dataFrame) {
            webSocketProcessor.broadcast((byte) 0x00, dataFrame.getTextPayload());
        }

        public void onClose(WebSocket webSocket) {
            webSocketProcessor.close();
        }

    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

    public class GrizzlyWebSocketSupport implements WebSocketSupport {

        private final WebSocket webSocket;

        public GrizzlyWebSocketSupport(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        public void writeError(int errorCode, String message) throws IOException {
        }

        public void redirect(String location) throws IOException {
        }

        public void write(byte frame, String data) throws IOException {
            webSocket.send(data);
        }

        public void write(byte frame, byte[] data) throws IOException {
            webSocket.send(new String(data));
        }

        public void write(byte frame, byte[] data, int offset, int length) throws IOException {
            webSocket.send(new String(data, offset, length));
        }

        public void close() throws IOException {
            webSocket.close();
        }
    }
}