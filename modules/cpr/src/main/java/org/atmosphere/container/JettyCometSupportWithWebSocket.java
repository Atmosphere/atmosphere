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
 * WebSocket Portable Runtime implementation on top of Jetty's.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyCometSupportWithWebSocket extends Jetty7CometSupport {

    private static final Logger logger = LoggerFactory.getLogger(JettyCometSupportWithWebSocket.class);
    private final WebSocketFactory webSocketFactory;

    public JettyCometSupportWithWebSocket(final AtmosphereConfig config) {
        super(config);

        String[] jettyVersion = config.getServletContext().getServerInfo().substring(6).split("\\.");
        if (Integer.valueOf(jettyVersion[0]) > 7 || Integer.valueOf(jettyVersion[0]) == 7 && Integer.valueOf(jettyVersion[1]) > 4) {
            webSocketFactory = JettyWebSocketUtil.getFactory(config);
        } else {
            webSocketFactory = null;
        }
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