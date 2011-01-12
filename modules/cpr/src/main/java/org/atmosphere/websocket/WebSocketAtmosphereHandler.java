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
 */
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Simple {@link AtmosphereHandler} which redirect the first request to the web application welcome page.
 * Once the WebSocket upgrade happens, this class just invoke the {@link #upgrade(AtmosphereResource)} method.
 * <p/>
 * Application should override the {@link #upgrade(AtmosphereResource)}.
 * Application should override the {@link #onStateChange} if they do not want to reflect/send back all Websocket
 * messages to all connections.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketAtmosphereHandler extends AbstractReflectorAtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketAtmosphereHandler.class);

    /**
     * This method redirect the request to the server main page (index.html, index.jsp, etc.) and then execute the
     * {@link #upgrade(AtmosphereResource)}.
     *
     * @param r The {@link AtmosphereResource}
     * @throws IOException
     */
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> r) throws IOException {
        if (!r.getResponse().getClass().isAssignableFrom(WebSocketHttpServletResponse.class)) {
            try {
                r.getAtmosphereConfig().getServletContext()
                        .getNamedDispatcher(r.getAtmosphereConfig().getDispatcherName())
                        .forward(r.getRequest(), r.getResponse());
            }
            catch (ServletException e) {
                IOException ie = new IOException();
                ie.initCause(e);
                throw ie;
            }
        }
        else {
            upgrade(r);
        }
    }

    /**
     * WebSocket upgrade. This is usually inside that method that you decide if a connection
     * needs to be suspended or not. Override this method for specific operations like configuring their own
     * {@link Broadcaster}, {@link BroadcastFilter} , {@link BroadcasterCache} etc.
     *
     * @param resource an {@link AtmosphereResource}
     * @throws IOException
     */
    public void upgrade(AtmosphereResource<HttpServletRequest, HttpServletResponse> resource) throws IOException {
        logger.debug("Suspending request: {}", resource.getRequest());
        resource.suspend(-1, false);
    }

    public void destroy() {
    }

}
