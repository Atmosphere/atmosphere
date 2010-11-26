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
package org.atmosphere.cpr;

import org.atmosphere.websocket.WebSocketHttpServletResponse;
import org.atmosphere.websocket.WebSocketSupport;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Like the {@link AsynchronousProcessor} class, this class is responsible for dispatching WebSocket request to the
 * proper {@link WebSocketSupport} implementation.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketProcessor implements Serializable {
    private final AtmosphereServlet atmosphereServlet;

    private AtomicBoolean loggedMsg = new AtomicBoolean(false);
    private AtmosphereResource r;
    private AtmosphereHandler ah;
    private final WebSocketSupport webSocketSupport;

    public WebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocketSupport webSocketSupport) {
        this.webSocketSupport = webSocketSupport;
        this.atmosphereServlet = atmosphereServlet;
    }

    public void connect(final HttpServletRequest request) throws IOException {
        if (!loggedMsg.getAndSet(true)) {
            AtmosphereServlet.logger.info("Atmosphere detected WebSocketSupport: " + webSocketSupport.getClass().getName());
        }
        request.setAttribute(WebSocketSupport.WEBSOCKET_SUSPEND, "true");
        try {
            atmosphereServlet.doCometSupport(request, new WebSocketHttpServletResponse<WebSocketSupport>(webSocketSupport));
        } catch (IOException e) {
            AtmosphereServlet.logger.log(Level.INFO, "", e);
        } catch (ServletException e) {
            AtmosphereServlet.logger.log(Level.INFO, "", e);
        }

        r = (AtmosphereResource) request.getAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE);
        ah = (AtmosphereHandler) request.getAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER);
        if (r == null || !r.getAtmosphereResourceEvent().isSuspended()) {
            webSocketSupport.close();
        }
    }

    public void broadcast(byte frame, String data) {
        r.getBroadcaster().broadcast(data);
    }

    public void broadcast(byte frame, byte[] data, int offset, int length) {

        byte[] b = new byte[length];
        System.arraycopy(data, offset, b, 0, length);
        r.getBroadcaster().broadcast(b);
    }

    public void close() {
        try {
            if (ah != null && r != null) {
                ah.onStateChange(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) r, false, true));
            }
        } catch (IOException e) {
            if (AtmosphereResourceImpl.class.isAssignableFrom(r.getClass())) {
                AtmosphereResourceImpl.class.cast(r).onThrowable(e);
            }
            AtmosphereServlet.logger.log(Level.INFO, "", e);
        }
    }
}
