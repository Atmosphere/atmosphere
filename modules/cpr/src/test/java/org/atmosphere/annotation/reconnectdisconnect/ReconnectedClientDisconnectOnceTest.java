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
package org.atmosphere.annotation.reconnectdisconnect;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * A client that reconnects to a restarted server carries its previous
 * tracking id, which the new server has never seen. When it then leaves, a
 * room service that announces the departure from {@code @Disconnect} (as the
 * AI classroom does with its presence frame) broadcasts to the room while the
 * leaving resource is still subscribed; that delivery used to run
 * {@code @Disconnect} again, once per announcement, in a burst on the write
 * thread. {@code @Disconnect} must fire once per resource (Invariant #2).
 */
class ReconnectedClientDisconnectOnceTest {

    /** {@code @Disconnect} calls, per resource uuid. */
    static final Map<String, AtomicInteger> DISCONNECTS = new ConcurrentHashMap<>();

    @ManagedService(path = "/classroom")
    public static final class RoomService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) throws Exception {
            int calls = DISCONNECTS.computeIfAbsent(event.getResource().uuid(), k -> new AtomicInteger())
                    .incrementAndGet();
            var presence = event.getResource().getBroadcaster().broadcast("leave " + event.getResource().uuid());
            if (calls == 1) {
                // The room hears about it while the leaving resource is still subscribed.
                try {
                    presence.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException ignored) {
                    // Delivery outcome is asserted on the counter.
                }
            }
        }
    }

    private AtmosphereFramework framework;

    @AfterEach
    void tearDown() {
        if (framework != null) {
            framework.destroy();
        }
    }

    private void startServer() throws ServletException {
        if (framework != null) {
            framework.destroy();
        }
        DISCONNECTS.clear();
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(RoomService.class);
        framework.addInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false");
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res)
                    throws IOException, ServletException {
                return suspended(req, res);
            }
        });
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    private WebSocket connect(WebSocketProcessor processor, String trackingId) throws IOException {
        var webSocket = new NoopWebSocket(framework);
        var builder = new AtmosphereRequestImpl.Builder().destroyable(false).pathInfo("/classroom");
        if (trackingId != null) {
            builder.headers(Map.of(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, trackingId));
        }
        AtmosphereRequest request = builder.build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        return webSocket;
    }

    @Test
    void clientReconnectedToARestartedServerFiresDisconnectOnceOnLeave() throws Exception {
        String trackingId = UUID.randomUUID().toString();

        // First server: the client joins, then the server goes away.
        startServer();
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        connect(processor, trackingId);

        // Restarted server: the client rejoins with the tracking id it already had,
        // next to a student who never left.
        startServer();
        processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var peer = connect(processor, null);
        var rejoined = connect(processor, trackingId);
        assertEquals(trackingId, rejoined.resource().uuid(), "precondition: the stale tracking id is reused");

        // The room is busy when the student leaves.
        var broadcaster = rejoined.resource().getBroadcaster();
        for (int i = 0; i < 5; i++) {
            broadcaster.broadcast("reply-" + i);
        }
        processor.close(rejoined, 1000);

        // Let any delivery still in flight land before counting.
        broadcaster.broadcast("after").get(5, TimeUnit.SECONDS);
        assertEquals(1, DISCONNECTS.getOrDefault(trackingId, new AtomicInteger()).get(),
                "@Disconnect must fire exactly once for the reconnected client");
        assertEquals(0, DISCONNECTS.getOrDefault(peer.resource().uuid(), new AtomicInteger()).get(),
                "the student who stayed must not be disconnected");
    }

    private static final class NoopWebSocket extends WebSocket {

        NoopWebSocket(AtmosphereFramework framework) {
            super(framework.getAtmosphereConfig());
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) {
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) {
            return this;
        }

        @Override
        public void close() {
        }
    }
}
