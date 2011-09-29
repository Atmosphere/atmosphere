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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Like the {@link AsynchronousProcessor} class, this class is responsible for dispatching WebSocket request to the
 * proper {@link WebSocketSupport} implementation.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class WebSocketProcessor implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketProcessor.class);

    private final AtmosphereServlet atmosphereServlet;
    private final WebSocketSupport webSocketSupport;

    private final AtomicBoolean loggedMsg = new AtomicBoolean(false);

    private AtmosphereResource<HttpServletRequest, HttpServletResponse> resource;
    private AtmosphereHandler handler;

    public WebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocketSupport webSocketSupport) {
        this.webSocketSupport = webSocketSupport;
        this.atmosphereServlet = atmosphereServlet;
    }

    public final void connect(final HttpServletRequest request) throws IOException {
        if (!loggedMsg.getAndSet(true)) {
            logger.info("Atmosphere detected WebSocketSupport: {}", webSocketSupport.getClass().getName());
        }

        request.setAttribute(WebSocketSupport.WEBSOCKET_SUSPEND, "true");
        try {
            atmosphereServlet
                    .doCometSupport(request, new WebSocketHttpServletResponse<WebSocketSupport>(webSocketSupport));
        } catch (IOException e) {
            logger.info("failed invoking atmosphere servlet doCometSupport()", e);
        } catch (ServletException e) {
            logger.info("failed invoking atmosphere servlet doCometSupport()", e);
        }

        resource = (AtmosphereResource) request.getAttribute(AtmosphereServlet.ATMOSPHERE_RESOURCE);
        handler = (AtmosphereHandler) request.getAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER);
        if (resource == null || !resource.getAtmosphereResourceEvent().isSuspended()) {
            logger.error("No AtmosphereResource has been suspended. The WebSocket will be closed.");
            webSocketSupport.close();
        }
    }

    public AtmosphereResource resource() {
        return resource;
    }

    public AtmosphereServlet atmosphereServlet() {
        return atmosphereServlet;
    }

    public HttpServletRequest request() {
        return resource.getRequest();
    }

    public WebSocketSupport webSocketSupport() {
        return webSocketSupport;
    }

    abstract public void broadcast(String data);

    abstract public void broadcast(byte[] data, int offset, int length);

    public void close() {
        try {
            if (handler != null && resource != null) {
                handler.onStateChange(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) resource, false, true));
            }
        } catch (IOException e) {
            if (AtmosphereResourceImpl.class.isAssignableFrom(resource.getClass())) {
                AtmosphereResourceImpl.class.cast(resource).onThrowable(e);
            }
            logger.info("Failed invoking atmosphere handler onStateChange()", e);
        }
    }

    @Override
    public String toString() {
        return "WebSocketProcessor{ handler=" + handler + ", resource=" + resource + ", webSocketSupport=" +
                webSocketSupport + " }";
    }
}
