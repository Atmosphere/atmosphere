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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Like the {@link org.atmosphere.cpr.AsynchronousProcessor} class, this class is responsible for dispatching WebSocket request to the
 * proper {@link org.atmosphere.websocket.WebSocket} implementation. This class can be extended in order to support any protocol
 * running on top  websocket.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class WebSocketProcessor implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketProcessor.class);

    private final AtmosphereServlet atmosphereServlet;
    private final WebSocket webSocket;

    private final AtomicBoolean loggedMsg = new AtomicBoolean(false);

    private AtmosphereResource<HttpServletRequest, HttpServletResponse> resource;
    private AtmosphereHandler handler;

    public WebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocket webSocket) {
        this.webSocket = webSocket;
        this.atmosphereServlet = atmosphereServlet;
    }

    public final void dispatch(final HttpServletRequest request) throws IOException {
        if (!loggedMsg.getAndSet(true)) {
            logger.info("Atmosphere detected WebSocket: {}", webSocket.getClass().getName());
        }

        WebSocketHttpServletResponse wsr = new WebSocketHttpServletResponse<WebSocket>(webSocket);
        WebSocketHttpServletRequest r = new WebSocketHttpServletRequest.Builder()
                .request(request)
                .headers(configureHeader(request))
                .build();

        request.setAttribute(WebSocket.WEBSOCKET_SUSPEND, true);
        dispatch(r, wsr);

        resource = (AtmosphereResource) request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
        handler = (AtmosphereHandler) request.getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);
        if (resource == null || !resource.getAtmosphereResourceEvent().isSuspended()) {
            logger.error("No AtmosphereResource has been suspended. The WebSocket will be closed.");
            webSocket.close();
        }
    }

    /**
     * Dispatch to request/response to the {@link org.atmosphere.cpr.CometSupport} implementation as it was a normal HTTP request.
     *
     * @param request a {@link HttpServletRequest}
     * @param response a {@link HttpServletResponse}
     */
    protected final void dispatch(final HttpServletRequest request, final HttpServletResponse response) {
        try {
            atmosphereServlet.doCometSupport(request, response);
        } catch (IOException e) {
            logger.info("failed invoking atmosphere servlet doCometSupport()", e);
        } catch (ServletException e) {
            logger.info("failed invoking atmosphere servlet doCometSupport()", e);
        }
    }

    public AtmosphereResource resource() {
        return resource;
    }

    public HttpServletRequest request() {
        return resource.getRequest();
    }

    public WebSocket webSocketSupport() {
        return webSocket;
    }

    /**
     * Parse the WebSocket message, and delegate the processing to the {@link AtmosphereServlet#cometSupport} or
     * to any existing technology. Invoking  {@link AtmosphereServlet#cometSupport} will delegate the request processing
     * to the {@link AtmosphereHandler} implementation. As an example, this is how Websocket messages are delegated to the
     * Jersey runtime.
     *
     * @param data The Websocket message
     */
    abstract public void parseMessage(String data);

    /**
     * Parse the WebSocket message, and delegate the processing to the {@link AtmosphereServlet#cometSupport} or
     * to any existing technology. Invoking  {@link AtmosphereServlet#cometSupport} will delegate the request processing
     * to the {@link AtmosphereHandler} implementation. As an example, this is how Websocket messages are delegated to the
     * Jersey runtime.
     *
     * @param data   The Websocket message
     * @param offset offset message index
     * @param length length of the message.
     */
    abstract public void parseMessage(byte[] data, int offset, int length);

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

        if (resource != null) {
            resource.getBroadcaster().removeAtmosphereResource(resource);
        }
    }

    @Override
    public String toString() {
        return "WebSocketProcessor{ handler=" + handler + ", resource=" + resource + ", webSocket=" +
                webSocket + " }";
    }

    public void notifyListener(WebSocketEventListener.WebSocketEvent event) {

        if (resource == null) return;

        AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(resource);

        for (AtmosphereResourceEventListener l : r.atmosphereResourceEventListener()) {
            if (WebSocketEventListener.class.isAssignableFrom(l.getClass())) {
                switch (event.type()) {
                    case CONNECT:
                        WebSocketEventListener.class.cast(l).onConnect(event);
                        break;
                    case DISCONNECT:
                        WebSocketEventListener.class.cast(l).onDisconnect(event);
                        break;
                    case CONTROL:
                        WebSocketEventListener.class.cast(l).onControl(event);
                        break;
                    case MESSAGE:
                        WebSocketEventListener.class.cast(l).onMessage(event);
                        break;
                    case HANDSHAKE:
                        WebSocketEventListener.class.cast(l).onHandshake(event);
                        break;
                    case CLOSE:
                        WebSocketEventListener.class.cast(l).onClose(event);
                        break;
                }
            }
        }
    }

    protected Map<String, String> configureHeader(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<String, String>();

        Enumeration<String> e = request.getParameterNames();
        String s;
        while (e.hasMoreElements()) {
            s = e.nextElement();
            headers.put(s, request.getParameter(s));
        }
        return headers;
    }
}
