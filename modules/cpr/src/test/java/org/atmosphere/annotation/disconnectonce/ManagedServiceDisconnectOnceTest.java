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
package org.atmosphere.annotation.disconnectonce;

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
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * A broadcast that reaches a resource while it is closing used to run
 * {@code @Disconnect} again: the resource is still subscribed and in scope
 * during {@code completeLifecycle}, but its event is already cancelled, so
 * {@code ManagedAtmosphereHandler.onStateChange} took the delivery for a
 * second disconnect. A WebTransport close produced that broadcast (the CLOSE
 * capsule dispatched as a message, a presence "left" message, an in-flight AI
 * stream) and the sweep logged {@code @Disconnect} 2, 3 and 19 times for one
 * client. {@code @Disconnect} must fire once per resource (Invariant #2).
 */
class ManagedServiceDisconnectOnceTest {

    static final AtomicInteger DISCONNECTS = new AtomicInteger();

    @ManagedService(path = "/disconnect-once")
    public static final class RoomService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) throws Exception {
            if (DISCONNECTS.incrementAndGet() == 1) {
                // Like a room announcing "left": the broadcast still reaches
                // the closing resource, which has not been removed yet.
                try {
                    event.getResource().getBroadcaster().broadcast("left").get(5, TimeUnit.SECONDS);
                } catch (TimeoutException ignored) {
                    // Delivery outcome is asserted on the counter below.
                }
            }
        }
    }

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() throws Exception {
        DISCONNECTS.set(0);
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

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void broadcastReachingAClosingResourceDoesNotRunDisconnectAgain() throws Exception {
        var webSocket = new NoopWebSocket(framework);
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/disconnect-once").build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));

        processor.close(webSocket, 1000);
        // A second close of the same connection must not fire it either.
        processor.close(webSocket, 1000);

        assertEquals(1, DISCONNECTS.get(), "@Disconnect must fire exactly once per resource");
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
