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
package org.atmosphere.websocket;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.VoidExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.FrameworkConfig.ASYNCHRONOUS_HOOK;

/**
 * Like the {@link org.atmosphere.cpr.AsynchronousProcessor} class, this class is responsible for dispatching WebSocket request to the
 * proper {@link org.atmosphere.websocket.WebSocket} implementation. This class can be extended in order to support any protocol
 * running on top  websocket.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketProcessor implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketProcessor.class);

    private final AtmosphereFramework framework;
    private final WebSocket webSocket;
    private final WebSocketProtocol webSocketProtocol;
    private final AtomicBoolean loggedMsg = new AtomicBoolean(false);
    private final boolean recycleAtmosphereRequestResponse;
    private final boolean executeAsync;
    private final ExecutorService asyncExecutor;

    public WebSocketProcessor(AtmosphereFramework framework, WebSocket webSocket, WebSocketProtocol webSocketProtocol) {
        this.webSocket = webSocket;
        this.framework = framework;
        this.webSocketProtocol = webSocketProtocol;

        String s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
        if (s != null && Boolean.valueOf(s)) {
            recycleAtmosphereRequestResponse = true;
        } else {
            recycleAtmosphereRequestResponse = false;
        }

        s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_PROTOCOL_EXECUTION);
        if (s != null && Boolean.valueOf(s)) {
            executeAsync = true;
            asyncExecutor = Executors.newCachedThreadPool();
        } else {
            executeAsync = false;
            asyncExecutor = VoidExecutorService.VOID;
        }
    }

    public final void dispatch(final AtmosphereRequest request) throws IOException {
        if (!loggedMsg.getAndSet(true)) {
            logger.debug("Atmosphere detected WebSocket: {}", webSocket.getClass().getName());
        }

        String pathInfo = request.getPathInfo();
        String requestURI = request.getRequestURI();

        AtmosphereResponse wsr = new AtmosphereResponse(webSocket, webSocketProtocol, request);
        AtmosphereRequest r = new AtmosphereRequest.Builder()
                .request(request)
                .pathInfo(pathInfo)
                .requestURI(requestURI)
                .headers(configureHeader(request))
                .build();

        request.setAttribute(WebSocket.WEBSOCKET_SUSPEND, true);

        dispatch(r, wsr);

        webSocketProtocol.onOpen(webSocket);

        if (webSocket.resource() != null) {
            if (!webSocket.resource().getAtmosphereResourceEvent().isSuspended()) {
                webSocketProtocol.onError(webSocket,
                        new WebSocketException("No AtmosphereResource has been suspended. The WebSocket will be closed:  " + request.getRequestURI(), wsr));
            } else {
                request.setAttribute(ASYNCHRONOUS_HOOK,
                        new AsynchronousProcessor.AsynchronousProcessorHook((AtmosphereResourceImpl) webSocket.resource()));
            }
        }
    }

    public void invokeWebSocketProtocol(String webSocketMessage) {
        List<AtmosphereRequest> list = webSocketProtocol.onMessage(webSocket, webSocketMessage);
        if (list == null) return;

        for (final AtmosphereRequest r : list) {
            if (r != null) {
                asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        AtmosphereResponse w = new AtmosphereResponse(webSocket, webSocketProtocol, r);
                        try {
                            dispatch(r, w);
                        } finally {
                            if (recycleAtmosphereRequestResponse) {
                                r.destroy();
                                w.destroy();
                            }
                        }
                    }
                });
            }
        }
    }

    public void invokeWebSocketProtocol(byte[] data, int offset, int length) {
        List<AtmosphereRequest> list = webSocketProtocol.onMessage(webSocket, data, offset, length);
        if (list == null) return;

        for (final AtmosphereRequest r : list) {
            if (r != null) {
                asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        AtmosphereResponse w = new AtmosphereResponse(webSocket, webSocketProtocol, r);
                        try {
                            dispatch(r, w);
                        } finally {
                            if (recycleAtmosphereRequestResponse) {
                                r.destroy();
                                w.destroy();
                            }
                        }
                    }
                });
            }
        }
    }

    /**
     * Dispatch to request/response to the {@link org.atmosphere.cpr.CometSupport} implementation as it was a normal HTTP request.
     *
     * @param request a {@link AtmosphereRequest}
     * @param r       a {@link AtmosphereResponse}
     */
    protected final void dispatch(final AtmosphereRequest request, final AtmosphereResponse r) {
        if (request == null) return;
        try {
            framework.doCometSupport(request, r);
        } catch (Throwable e) {
            logger.warn("Failed invoking AtmosphereFramework.doCometSupport()", e);
            webSocketProtocol.onError(webSocket, new WebSocketException(e,
                    new AtmosphereResponse.Builder()
                            .request(request)
                            .status(500)
                            .statusMessage("Server Error").build()));
            return;
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

    public void close(int closeCode) {
        logger.debug("WebSocket closed with {}", closeCode);
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();
        AtmosphereRequest r = resource.getRequest(false);
        AtmosphereResponse s = resource.getResponse(false);
        try {
            webSocketProtocol.onClose(webSocket);

            if (resource != null && resource.isInScope()) {
                AsynchronousProcessor.AsynchronousProcessorHook h = (AsynchronousProcessor.AsynchronousProcessorHook)
                        r.getAttribute(ASYNCHRONOUS_HOOK);
                if (h != null) {
                    if (closeCode == 1000) {
                        h.timedOut();
                    } else {
                        h.closed();
                    }
                } else {
                    logger.warn("AsynchronousProcessor.AsynchronousProcessorHook was null");
                }
            }
        } finally {
            if (r != null && AtmosphereRequest.class.isAssignableFrom(r.getClass())) {
                r.destroy();
            }

            if (s != null && AtmosphereResponse.class.isAssignableFrom(s.getClass())) {
                s.destroy();
            }

            if (webSocket != null) {
                WebSocketAdapter.class.cast(webSocket).setAtmosphereResource(null);
            }
        }
    }

    @Override
    public String toString() {
        return "WebSocketProcessor{ webSocket=" + webSocket + " }";
    }

    public void notifyListener(WebSocketEventListener.WebSocketEvent event) {
        AtmosphereResource resource =
                (AtmosphereResource) webSocket.resource();
        if (resource == null) return;

        AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(resource);

        for (AtmosphereResourceEventListener l : r.atmosphereResourceEventListener()) {
            if (WebSocketEventListener.class.isAssignableFrom(l.getClass())) {
                try {
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
                } catch (Throwable t) {
                    logger.debug("Listener error {}", t);
                    try {
                        WebSocketEventListener.class.cast(l).onThrowable(new AtmosphereResourceEventImpl(r, false, false, t));
                    } catch (Throwable t2) {
                        logger.warn("Listener error {}", t2);
                    }
                }
            }
        }
    }

    public static final Map<String, String> configureHeader(AtmosphereRequest request) {
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
