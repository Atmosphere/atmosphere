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
package org.atmosphere.annotation.cleanclose;

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
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.interceptor.IdleResourceInterceptor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.CLOSED_ATMOSPHERE_THINK_TIME;
import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A WebSocket client sends no Atmosphere close message, so the close code is
 * the only way to tell a user who left from a dropped connection. A normal
 * (1000) or going-away (1001) close is the client closing the connection and
 * must reach {@code @Disconnect} as closed by the client, not as an
 * unexpected disconnect (Invariant #5); an abnormal close stays cancelled.
 */
class WebSocketCleanCloseTest {

    /** The flags {@code @Disconnect} saw: {closedByClient, cancelled}. */
    static final AtomicReference<boolean[]> SEEN = new AtomicReference<>();
    /** How many times {@code @Disconnect} ran. */
    static final AtomicInteger CALLS = new AtomicInteger();
    /** The WebSocket close code {@code @Disconnect} saw. */
    static final AtomicInteger CODE = new AtomicInteger();

    @ManagedService(path = "/clean-close")
    public static final class LeaveService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            CODE.set(event instanceof AtmosphereResourceEventImpl impl ? impl.webSocketCloseCode() : -2);
            SEEN.set(new boolean[] {event.isClosedByClient(), event.isCancelled()});
            CALLS.incrementAndGet();
        }
    }

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() {
        SEEN.set(null);
        CALLS.set(0);
        CODE.set(0);
    }

    private void start(String thinkTimeMillis) throws ServletException {
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(LeaveService.class);
        if (thinkTimeMillis != null) {
            framework.addInitParameter(CLOSED_ATMOSPHERE_THINK_TIME, thinkTimeMillis);
        }
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
        if (framework != null) {
            framework.destroy();
        }
    }

    private boolean[] openThenClose(int closeCode) throws IOException, InterruptedException {
        var webSocket = new NoopWebSocket(framework);
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/clean-close").build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        processor.close(webSocket, closeCode);
        // A configured think time delays the close on the scheduler.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (SEEN.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        var seen = SEEN.get();
        assertNotNull(seen, "@Disconnect must run for close code " + closeCode);
        return seen;
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 1001})
    void cleanCloseIsClosedByTheClient(int closeCode) throws Exception {
        start(null);
        var seen = openThenClose(closeCode);
        assertTrue(seen[0], "a " + closeCode + " close is the client leaving");
        assertFalse(seen[1], "a " + closeCode + " close is not an unexpected disconnect");
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 1001, 1006})
    void theCloseCodeReachesTheDisconnect(int closeCode) throws Exception {
        // 1000 and 1001 both read as closed by the client; the code is what
        // tells a client that left (1000) from a page that reloads (1001).
        start(null);
        openThenClose(closeCode);
        assertEquals(closeCode, CODE.get(), "@Disconnect sees the close code of the WebSocket");
    }

    @ParameterizedTest
    @ValueSource(ints = {1006, 1011})
    void abnormalCloseIsCancelled(int closeCode) throws Exception {
        start(null);
        var seen = openThenClose(closeCode);
        assertFalse(seen[0], "a " + closeCode + " close is not the client leaving");
        assertTrue(seen[1], "a " + closeCode + " close is an unexpected disconnect");
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 1001})
    void delayedCleanCloseIsClosedByTheClient(int closeCode) throws Exception {
        start("50");
        var seen = openThenClose(closeCode);
        assertTrue(seen[0], "a delayed " + closeCode + " close is the client leaving");
        assertFalse(seen[1], "a delayed " + closeCode + " close is not an unexpected disconnect");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "50")
    void idleEvictionIsCancelledNotClosedByTheClient(String thinkTimeMillis) throws Exception {
        start(thinkTimeMillis);
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var webSocket = new ContainerWebSocket(framework, processor);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/clean-close").build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        var resource = (AtmosphereResourceImpl) webSocket.resource();
        assertNotNull(resource, "the WebSocket must be open");
        // The client's network vanished a minute ago: nothing came from it since.
        resource.getRequest(false).setAttribute(MAX_INACTIVE, System.currentTimeMillis() - 60_000);

        var idle = new IdleResourceInterceptor();
        idle.configure(framework.getAtmosphereConfig());
        try {
            // Starts the eviction sweep, which runs at once.
            idle.maxInactiveTime(1_000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (SEEN.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } finally {
            idle.destroy();
        }

        var seen = SEEN.get();
        assertNotNull(seen, "@Disconnect must run for an evicted client");
        assertTrue(webSocket.closed, "the eviction closes the WebSocket");
        assertFalse(seen[0], "the server evicted the client: the client did not leave");
        assertTrue(seen[1], "an evicted client is an unexpected disconnect");
        // Past any configured think time: no second dispatch.
        Thread.sleep(200);
        assertEquals(1, CALLS.get(), "@Disconnect must run once for an evicted client");
    }

    @Test
    void frameworkDestroyIsNotTheClientLeaving() throws Exception {
        start(null);
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var webSocket = new ContainerWebSocket(framework, processor);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/clean-close").build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        assertNotNull(webSocket.resource(), "the WebSocket must be open");

        framework.destroy();

        // The shutdown closes the WebSocket, and the container reports a 1000 close.
        assertTrue(webSocket.closed, "the shutdown closes the WebSocket");
        var seen = SEEN.get();
        assertTrue(seen == null || !seen[0], "the server shut down: the client did not leave");
    }

    /**
     * Closes like a servlet container: closing the session from the server
     * reports a normal (1000) close to the endpoint at once, as Tomcat's
     * {@code WsSession.close()} does.
     */
    private static final class ContainerWebSocket extends WebSocket {

        private final WebSocketProcessor processor;
        volatile boolean closed;

        ContainerWebSocket(AtmosphereFramework framework, WebSocketProcessor processor) {
            super(framework.getAtmosphereConfig());
            this.processor = processor;
        }

        @Override
        public boolean isOpen() {
            return !closed;
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
            if (!closed) {
                closed = true;
                processor.close(this, 1000);
            }
        }
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
