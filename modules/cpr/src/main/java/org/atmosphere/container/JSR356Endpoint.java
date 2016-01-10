/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.container;

import org.atmosphere.container.version.JSR356WebSocket;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.util.IOUtils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.atmosphere.cpr.ApplicationConfig.ALLOW_QUERYSTRING_AS_REQUEST;

public class JSR356Endpoint extends Endpoint {

    private static final Logger logger = LoggerFactory.getLogger(JSR356Endpoint.class);

    private final static String JAVAX_WEBSOCKET_ENDPOINT_LOCAL_ADDRESS = "javax.websocket.endpoint.localAddress";
    private final static String JAVAX_WEBSOCKET_ENDPOINT_REMOTE_ADDRESS = "javax.websocket.endpoint.remoteAddress";

    private final WebSocketProcessor webSocketProcessor;
    private final Integer maxBinaryBufferSize;
    private final Integer maxTextBufferSize;
    private AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private WebSocket webSocket;
    private final int webSocketWriteTimeout;
    private HandshakeRequest handshakeRequest;

    public JSR356Endpoint(AtmosphereFramework framework, WebSocketProcessor webSocketProcessor) {
        this.framework = framework;
        this.webSocketProcessor = webSocketProcessor;

        if (framework.isUseNativeImplementation()) {
            throw new IllegalStateException("You cannot use WebSocket native implementation with JSR356. Please set " + ApplicationConfig.PROPERTY_NATIVE_COMETSUPPORT + " to false");
        }

        String s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME);
        if (s != null) {
            webSocketWriteTimeout = Integer.valueOf(s);
        } else {
            webSocketWriteTimeout = -1;
        }

        s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_MAXBINARYSIZE);
        if (s != null) {
            maxBinaryBufferSize = Integer.valueOf(s);
        } else {
            maxBinaryBufferSize = -1;
        }

        s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE);
        if (s != null) {
            maxTextBufferSize = Integer.valueOf(s);
        } else {
            maxTextBufferSize = -1;
        }
    }

    public JSR356Endpoint handshakeRequest(HandshakeRequest handshakeRequest) {
        this.handshakeRequest = handshakeRequest;
        return this;
    }

    @Override
    public void onOpen(Session session, final EndpointConfig endpointConfig) {

        if (framework.isDestroyed()) return;

        if (!session.isOpen()) {
            logger.trace("Session Closed {}", session);
            return;
        }

        if (maxBinaryBufferSize != -1) session.setMaxBinaryMessageBufferSize(maxBinaryBufferSize);
        if (webSocketWriteTimeout != -1) session.setMaxIdleTimeout(webSocketWriteTimeout);
        if (maxTextBufferSize != -1) session.setMaxTextMessageBufferSize(maxTextBufferSize);

        webSocket = new JSR356WebSocket(session, framework.getAtmosphereConfig());

        Map<String, String> headers = new HashMap<String, String>();
        // TODO: We don't support multi map header, which cause => https://github.com/Atmosphere/atmosphere/issues/1945
        for (Map.Entry<String, List<String>> e : handshakeRequest.getHeaders().entrySet()) {
            headers.put(e.getKey(), !e.getValue().isEmpty() ? e.getValue().get(0) : "");
        }

        // Force WebSocket. Hack for https://github.com/Atmosphere/atmosphere/issues/1944
        headers.put("Connection", "Upgrade");

        String servletPath = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.JSR356_MAPPING_PATH);
        if (servletPath == null) {
            servletPath = IOUtils.guestServletPath(framework.getAtmosphereConfig());
        }

        boolean recomputeForBackwardCompat = false;
        URI uri = session.getRequestURI();
        String rawPath = uri.getPath();
        String contextPath = framework.getAtmosphereConfig().getServletContext().getContextPath();
        int pathInfoStartAt = rawPath.indexOf(servletPath) + servletPath.length();

        String pathInfo = null;
        if (rawPath.length() >= pathInfoStartAt) {
            pathInfo = rawPath.substring(pathInfoStartAt);
        } else {
            recomputeForBackwardCompat = true;
        }

        if (recomputeForBackwardCompat) {
            // DON"T SCREAM this code is for broken/backward compatible
            String[] paths = uri.getPath() != null ? uri.getPath().split("/") : new String[]{};

            int pathInfoStartIndex = 3;
            if ("".equals(contextPath) || "".equals(servletPath)) {
                pathInfoStartIndex = 2;
            }
            ///contextPath / servletPath / pathInfo or / servletPath / pathInfo
            StringBuffer b = new StringBuffer("/");
            for (int i = 0; i < paths.length; i++) {
                if (i >= pathInfoStartIndex) {
                    b.append(paths[i]).append("/");
                }
            }

            if (b.length() > 1) {
                b.deleteCharAt(b.length() - 1);
            }

            pathInfo = b.toString();
        }

        if (pathInfo.equals("/")) {
            pathInfo = null;
        }

        try {
            String requestURL = uri.toASCIIString();
            if (requestURL.contains("?")) {
                requestURL = requestURL.substring(0, requestURL.indexOf("?"));
            }

            // https://issues.apache.org/bugzilla/show_bug.cgi?id=56573
            // https://java.net/jira/browse/WEBSOCKET_SPEC-228
            if ((!requestURL.startsWith("http://")) || (!requestURL.startsWith("https://"))) {
                if (requestURL.startsWith("/")) {
                    List<String> l = handshakeRequest.getHeaders().get("origin");
                    if (l == null) {
                        // https://issues.jboss.org/browse/UNDERTOW-252
                        l = handshakeRequest.getHeaders().get("Origin");
                    }
                    String origin;
                    if (l != null && !l.isEmpty()) {
                        origin = l.get(0);
                    } else {
                        // Broken WebSocket Spec
                        logger.trace("Unable to retrieve the `origin` header for websocket {}", session);
                        origin = new StringBuilder("http").append(session.isSecure() ? "s" : "").append("://0.0.0.0:80").toString();
                    }
                    requestURL = new StringBuilder(origin).append(requestURL).toString();
                } else if (requestURL.startsWith("ws://")) {
                    requestURL = requestURL.replace("ws://", "http://");
                } else if (requestURL.startsWith("wss://")) {
                    requestURL = requestURL.replace("wss://", "https://");
                }
            }

            request = new AtmosphereRequestImpl.Builder()
                    .requestURI(uri.getPath())
                    .requestURL(requestURL)
                    .headers(headers)
                    .session((HttpSession) handshakeRequest.getHttpSession())
                    .servletPath(servletPath)
                    .contextPath(framework.getServletContext().getContextPath())
                    .pathInfo(pathInfo)
                    .destroyable(false)
                    .userPrincipal(session.getUserPrincipal())
                    .remoteInetSocketAddress(new Callable<InetSocketAddress>() {
                        @Override
                        public InetSocketAddress call() throws Exception {
                            return (InetSocketAddress) endpointConfig.getUserProperties().get(JAVAX_WEBSOCKET_ENDPOINT_REMOTE_ADDRESS);
                        }
                    })
                    .localInetSocketAddress(new Callable<InetSocketAddress>() {
                        @Override
                        public InetSocketAddress call() throws Exception {
                            return (InetSocketAddress) endpointConfig.getUserProperties().get(JAVAX_WEBSOCKET_ENDPOINT_LOCAL_ADDRESS);
                        }
                    })
                    .build()
                    .queryString(session.getQueryString());

            if (!webSocketProcessor.handshake(request)) {
                try {
                    session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "Handshake not accepted."));
                } catch (IOException e) {
                    logger.trace("", e);
                }
                return;
            }

            // TODO: Fix this crazy code.
            framework.addInitParameter(ALLOW_QUERYSTRING_AS_REQUEST, "false");

            webSocketProcessor.open(webSocket, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));

            framework.addInitParameter(ALLOW_QUERYSTRING_AS_REQUEST, "true");

            if (session.isOpen()) {
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(String s) {
                        webSocketProcessor.invokeWebSocketProtocol(webSocket, s);
                    }
                });

                session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {
                    @Override
                    public void onMessage(ByteBuffer bb) {
                        byte[] b = bb.hasArray() ? bb.array() : new byte[bb.limit()];
                        bb.get(b);
                        webSocketProcessor.invokeWebSocketProtocol(webSocket, b, 0, b.length);
                    }
                });
            } else {
                logger.trace("Session closed during onOpen {}", session);
                onClose(session, new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Session closed already"));
            }
        } catch (Throwable e) {
            if (session.isOpen()){
                logger.error("", e);
            } else {
                logger.trace("Session closed during onOpen", e);
            }

            try {
                session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, e.getMessage()));
            } catch (IOException e1) {
                logger.trace("", e);
            }
            return;
        }

    }

    @Override
    public void onClose(javax.websocket.Session session, javax.websocket.CloseReason closeCode) {
        logger.trace("{} closed {}", session, closeCode);
        if (request != null) {
            request.destroy();
            webSocketProcessor.close(webSocket, closeCode.getCloseCode().getCode());
        }
    }

    @Override
    public void onError(javax.websocket.Session session, java.lang.Throwable t) {
        try {
            logger.error("", t);
            webSocketProcessor.notifyListener(webSocket,
                    new WebSocketEventListener.WebSocketEvent<Throwable>(t, WebSocketEventListener.WebSocketEvent.TYPE.EXCEPTION, webSocket));
        } catch (Exception ex) {
            // Ignore completely
            // https://github.com/Atmosphere/atmosphere/issues/1758
        }
    }
}