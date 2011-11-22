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

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.Meteor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
public class WebSocketProcessor implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketProcessor.class);

    private final AtmosphereServlet atmosphereServlet;
    private final WebSocket webSocket;
    private final WebSocketProtocol webSocketProtocol;

    private final AtomicBoolean loggedMsg = new AtomicBoolean(false);

    public WebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocket webSocket, WebSocketProtocol webSocketProtocol) {
        this.webSocket = webSocket;
        this.atmosphereServlet = atmosphereServlet;
        this.webSocketProtocol = webSocketProtocol;
    }

    public final void dispatch(final HttpServletRequest request) throws IOException {
        if (!loggedMsg.getAndSet(true)) {
            logger.debug("Atmosphere detected WebSocket: {}", webSocket.getClass().getName());
        }

        String pathInfo = request.getPathInfo();
        String requestURI = request.getRequestURI();
        if (atmosphereServlet.getAtmosphereConfig().getWebServerName().toLowerCase().indexOf("glassfish") != -1) {
            try {
                pathInfo = pathInfo.substring(pathInfo.indexOf("/", 1));
                requestURI = requestURI.substring(requestURI.indexOf("/", 1));
            } catch (IndexOutOfBoundsException e) {
                // Jersey will not work.
                logger.warn("Unable to patch GlassFish WebSocket http://java.net/jira/browse/GRIZZLY-1114");
            }
        }

        AtmosphereResponse wsr = new AtmosphereResponse<WebSocket>(webSocket, webSocketProtocol, request);
        AtmosphereRequest r = new AtmosphereRequest.Builder()
                .request(request)
                .pathInfo(pathInfo)
                .requestURI(requestURI)
                .headers(configureHeader(request))
                .build();

        request.setAttribute(WebSocket.WEBSOCKET_SUSPEND, true);

        dispatch(r, wsr);

        webSocketProtocol.onOpen(webSocket);

        if (!webSocket.resource().getAtmosphereResourceEvent().isSuspended()) {
            webSocketProtocol.onError(webSocket, new WebSocketException("No AtmosphereResource has been suspended. The WebSocket will be closed.", wsr));
        }
    }

    public void invokeWebSocketProtocol(String webSocketMessage) {
        AtmosphereRequest r = webSocketProtocol.onMessage(webSocket, webSocketMessage);
        if (r != null) {
            AtmosphereResponse<WebSocket> w = new AtmosphereResponse<WebSocket>(webSocket, webSocketProtocol, r);
            dispatch(r, w);
        }
    }

    public void invokeWebSocketProtocol(byte[] data, int offset, int length) {
        AtmosphereRequest r = webSocketProtocol.onMessage(webSocket, data, offset, length);
        if (r != null) {
            AtmosphereResponse<WebSocket> w = new AtmosphereResponse<WebSocket>(webSocket, webSocketProtocol, r);
            dispatch(r, w);
        }
    }

    /**
     * Dispatch to request/response to the {@link org.atmosphere.cpr.CometSupport} implementation as it was a normal HTTP request.
     *
     * @param request a {@link HttpServletRequest}
     * @param r       a {@link HttpServletResponse}
     */
    protected final void dispatch(final HttpServletRequest request, final AtmosphereResponse<?> r) {
        if (request == null) return;
        try {
            atmosphereServlet.doCometSupport(request, r);
        } catch (IOException e) {
            logger.warn("Failed invoking atmosphere servlet doCometSupport()", e);
        } catch (ServletException e) {
            logger.warn("Failed invoking atmosphere servlet doCometSupport()", e);
        }

        AtmosphereResource resource = (AtmosphereResource) request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);

        if (webSocket.resource() == null && WebSocketAdapter.class.isAssignableFrom(webSocket.getClass())) {
            WebSocketAdapter.class.cast(webSocket).setAtmosphereResource(resource);
        }

        if (r.getStatus() >= 400) {
            webSocketProtocol.onError(webSocket, new WebSocketException("Status code higher than 400", r));
        }
    }

    public WebSocket webSocket() {
        return webSocket;
    }

    public void close() {
        AtmosphereResource<HttpServletRequest, HttpServletResponse> resource =
                (AtmosphereResource<HttpServletRequest, HttpServletResponse>) webSocket.resource();
        try {
            webSocketProtocol.onClose(webSocket);

            if (resource != null) {
                AtmosphereHandler handler = (AtmosphereHandler) resource.getRequest().getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);
                synchronized (resource) {
                    if (handler != null) {
                        handler.onStateChange(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) resource, false, true));
                    }

                    Meteor m = (Meteor) resource.getRequest().getAttribute(AtmosphereResourceImpl.METEOR);
                    if (m != null) {
                        m.destroy();
                    }
                }

                try {
                    resource.notifyListeners();
                } finally {
                    AsynchronousProcessor.destroyResource(resource);
                }
            }
        } catch (IOException e) {
            if (resource != null && AtmosphereResourceImpl.class.isAssignableFrom(resource.getClass())) {
                AtmosphereResourceImpl.class.cast(resource).onThrowable(e);
            }
            logger.warn("Failed invoking atmosphere handler onStateChange()", e);
        }
    }

    @Override
    public String toString() {
        return "WebSocketProcessor{ webSocket=" + webSocket + " }";
    }

    public void notifyListener(WebSocketEventListener.WebSocketEvent event) {
        AtmosphereResource<HttpServletRequest, HttpServletResponse> resource =
                (AtmosphereResource<HttpServletRequest, HttpServletResponse>) webSocket.resource();
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

    public static final Map<String, String> configureHeader(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<String, String>();

        Enumeration<String> e = request.getParameterNames();
        String s;
        while (e.hasMoreElements()) {
            s = e.nextElement();
            headers.put(s, request.getParameter(s));
        }

        headers.put(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.WEBSOCKET_TRANSPORT);
        return headers;
    }

    public final static class WebSocketException extends Exception {

        private final AtmosphereResponse r;

        public WebSocketException(String s, AtmosphereResponse r) {
            super(s);
            this.r = r;
        }

        public WebSocketException(Throwable throwable, AtmosphereResponse r) {
            super(throwable);
            this.r = r;
        }

        public AtmosphereResponse response() {
            return r;
        }
    }
}
