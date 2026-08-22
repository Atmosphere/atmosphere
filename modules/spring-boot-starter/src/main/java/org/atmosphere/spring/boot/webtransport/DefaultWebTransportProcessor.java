/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.spring.boot.webtransport;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.webtransport.WebTransportEventListener;
import org.atmosphere.webtransport.WebTransportProcessor;
import org.atmosphere.webtransport.WebTransportSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link WebTransportProcessor} that delegates to the existing
 * {@link WebSocketProcessor} for backward compatibility. This allows all
 * existing Atmosphere handlers, interceptors, broadcasters, and protocol
 * handling to work transparently with WebTransport connections.
 *
 * <p>Each {@link WebTransportSession} is mapped to a bridge
 * {@link WebSocket} that forwards writes through the session's
 * bidirectional QUIC stream. The bridge is invisible to application code —
 * handlers see a standard WebSocket lifecycle.</p>
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultWebTransportProcessor implements WebTransportProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWebTransportProcessor.class);

    private volatile AtmosphereConfig config;
    private volatile WebSocketProcessor wsProcessor;
    private volatile org.atmosphere.webtransport.WebTransportProtocol customProtocol;
    private final Map<WebTransportSession, WebTransportBridgeWebSocket> bridges = new ConcurrentHashMap<>();

    @Override
    public WebTransportProcessor configure(AtmosphereConfig config) {
        this.config = config;
        this.wsProcessor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(config.framework());
        // The WEBTRANSPORT_PROTOCOL knob selects a custom
        // WebTransportProtocol implementation, mirroring the WebSocket
        // protocol selection — before this the knob was never read and the
        // advertised extension point did not exist. Unset keeps the
        // WebSocket-bridge delegation.
        var protocolClass = config.getInitParameter(
                org.atmosphere.cpr.ApplicationConfig.WEBTRANSPORT_PROTOCOL);
        if (protocolClass != null && !protocolClass.isBlank()) {
            try {
                var protocol = config.framework().newClassInstance(
                        org.atmosphere.webtransport.WebTransportProtocol.class,
                        Class.forName(protocolClass.trim(), true,
                                        Thread.currentThread().getContextClassLoader())
                                .asSubclass(org.atmosphere.webtransport.WebTransportProtocol.class));
                protocol.configure(config);
                this.customProtocol = protocol;
                logger.info("WebTransport protocol installed: {}", protocolClass.trim());
            } catch (Exception e) {
                // A configured-but-broken protocol must fail loudly, not
                // silently fall back to a different wire behaviour
                // (Runtime Truth, Invariant #5).
                throw new IllegalStateException("Configured "
                        + org.atmosphere.cpr.ApplicationConfig.WEBTRANSPORT_PROTOCOL
                        + "=" + protocolClass + " could not be instantiated", e);
            }
        }
        return this;
    }

    /**
     * Dispatch the requests a custom {@link org.atmosphere.webtransport.WebTransportProtocol}
     * produced through the framework, responses routed back over the
     * session's bridge. A {@code null} list means the protocol handled
     * dispatch manually.
     */
    private void dispatchProtocolRequests(WebTransportSession session,
                                          java.util.List<AtmosphereRequest> requests) {
        if (requests == null) {
            return;
        }
        var bridge = bridges.get(session);
        for (var request : requests) {
            try {
                var response = AtmosphereResponseImpl.newInstance(config, request, bridge);
                response.delegateToNativeResponse(false);
                config.framework().getAsyncSupport().service(request, response);
            } catch (Exception e) {
                logger.warn("WebTransportProtocol dispatch failed for session {}",
                        session.uuid(), e);
                customProtocol.onError(session,
                        new WebTransportProcessor.WebTransportException(e.getMessage(), null));
            }
        }
    }

    @Override
    public WebTransportProcessor registerWebTransportHandler(String path, WebTransportHandlerProxy handler) {
        // Handler registration is driven by annotation scanning in the framework;
        // WebSocket handlers are already registered via WebSocketProcessorFactory.
        return this;
    }

    @Override
    public void open(WebTransportSession session, AtmosphereRequest request, AtmosphereResponse response)
            throws IOException {
        var bridge = new WebTransportBridgeWebSocket(config, session);
        bridges.put(session, bridge);
        // Create a response backed by the bridge WebSocket so writes go through the session
        var bridgeResponse = AtmosphereResponseImpl.newInstance(config, request, bridge);
        bridgeResponse.delegateToNativeResponse(false);
        wsProcessor.open(bridge, request, bridgeResponse);
        if (customProtocol != null) {
            customProtocol.onOpen(session);
        }
        logger.debug("WebTransport session opened via bridge for {}", session.uuid());
    }

    @Override
    public void invokeWebTransportProtocol(WebTransportSession session, String message) {
        if (customProtocol != null) {
            dispatchProtocolRequests(session, customProtocol.onMessage(session, message));
            return;
        }
        var bridge = bridges.get(session);
        if (bridge != null) {
            wsProcessor.invokeWebSocketProtocol(bridge, message);
        } else {
            logger.warn("No bridge WebSocket found for session {}", session.uuid());
        }
    }

    @Override
    public void invokeWebTransportProtocol(WebTransportSession session, byte[] data, int offset, int length) {
        if (customProtocol != null) {
            dispatchProtocolRequests(session,
                    customProtocol.onMessage(session, data, offset, length));
            return;
        }
        var bridge = bridges.get(session);
        if (bridge != null) {
            wsProcessor.invokeWebSocketProtocol(bridge, data, offset, length);
        } else {
            logger.warn("No bridge WebSocket found for session {}", session.uuid());
        }
    }

    @Override
    public void close(WebTransportSession session, int closeCode) {
        if (customProtocol != null) {
            customProtocol.onClose(session);
        }
        var bridge = bridges.remove(session);
        if (bridge != null) {
            wsProcessor.close(bridge, closeCode);
            logger.debug("WebTransport session closed via bridge for {}", session.uuid());
        }
    }

    @Override
    public void notifyListener(WebTransportSession session, WebTransportEventListener.WebTransportEvent<?> event) {
        // Event notifications are handled by the WebSocketProcessor's listener chain
    }

    @Override
    public void destroy() {
        bridges.clear();
    }

    /**
     * Bridge {@link WebSocket} that delegates all writes to a
     * {@link WebTransportSession}. This makes WebTransport sessions
     * appear as regular WebSocket connections to the Atmosphere framework.
     */
    static class WebTransportBridgeWebSocket extends WebSocket {

        private final WebTransportSession session;

        WebTransportBridgeWebSocket(AtmosphereConfig config, WebTransportSession session) {
            super(config);
            this.session = session;
        }

        @Override
        public boolean isOpen() {
            return session.isOpen();
        }

        @Override
        public WebSocket write(String s) throws IOException {
            session.write(s);
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) throws IOException {
            session.write(b, offset, length);
            return this;
        }

        @Override
        public void close() {
            session.close();
        }

        WebTransportSession session() {
            return session;
        }
    }
}
