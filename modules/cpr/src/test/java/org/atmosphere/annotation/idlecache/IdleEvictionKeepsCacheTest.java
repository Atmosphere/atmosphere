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
package org.atmosphere.annotation.idlecache;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.interceptor.IdleResourceInterceptor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * An idle eviction ({@link IdleResourceInterceptor}) closes a connection the
 * server believes is dead, and the client reconnects under its tracking id: the
 * {@link UUIDBroadcasterCache} must keep what is broadcast in between for it,
 * exactly as it does when the client closes the connection itself.
 */
class IdleEvictionKeepsCacheTest {

    private static final String PATH = "/idle-cache";

    @ManagedService(path = PATH)
    public static final class CachedService {

        @Message
        public String onMessage(String message) {
            return message;
        }
    }

    private AtmosphereFramework framework;

    private void start() throws ServletException {
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(CachedService.class);
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

    private TestWebSocket open(boolean reportsCloseAtOnce) throws IOException {
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var webSocket = new TestWebSocket(framework, processor, reportsCloseAtOnce);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo(PATH).build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        assertNotNull(webSocket.resource(), "the WebSocket must be open");
        return webSocket;
    }

    private Broadcaster broadcaster() {
        Broadcaster b = framework.getBroadcasterFactory().lookup(PATH, false);
        assertNotNull(b, "the service's broadcaster");
        assertInstanceOf(UUIDBroadcasterCache.class, b.getBroadcasterConfig().getBroadcasterCache());
        return b;
    }

    private List<Object> missedAfterBroadcast(String uuid) throws Exception {
        Broadcaster b = broadcaster();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!b.getAtmosphereResources().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(b.getAtmosphereResources().isEmpty(), "the closed client left the broadcaster");
        b.broadcast("missed").get(5, TimeUnit.SECONDS);
        return b.getBroadcasterConfig().getBroadcasterCache().retrieveFromCache(b.getID(), uuid);
    }

    /**
     * @param reportsCloseAtOnce true for a container that reports a server-side
     *                           close to the endpoint at once (Tomcat), false for
     *                           one that reports it later, or never for a dead
     *                           peer (Netty, Jetty)
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void idleEvictedClientKeepsWhatIsBroadcastUntilItReconnects(boolean reportsCloseAtOnce) throws Exception {
        start();
        var webSocket = open(reportsCloseAtOnce);
        var resource = (AtmosphereResourceImpl) webSocket.resource();
        String uuid = resource.uuid();
        // Nothing came from the client for a minute.
        resource.getRequest(false).setAttribute(MAX_INACTIVE, System.currentTimeMillis() - 60_000);

        var idle = new IdleResourceInterceptor();
        idle.configure(framework.getAtmosphereConfig());
        try {
            // Starts the eviction sweep, which runs at once.
            idle.maxInactiveTime(1_000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!webSocket.closeCalled && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(webSocket.closeCalled, "the eviction closes the WebSocket");
            assertEquals(List.of("missed"), missedAfterBroadcast(uuid),
                    "the evicted client gets on reconnect what was broadcast while it was away");
            if (!reportsCloseAtOnce) {
                // The container's late close report finds the resource already ended.
                WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework).close(webSocket, 1000);
            }
        } finally {
            idle.destroy();
        }
    }

    @Test
    void clientClosedConnectionKeepsWhatIsBroadcastUntilItReconnects() throws Exception {
        start();
        var webSocket = open(true);
        String uuid = webSocket.resource().uuid();
        WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework).close(webSocket, 1000);
        assertEquals(List.of("missed"), missedAfterBroadcast(uuid),
                "the client that closed gets on reconnect what was broadcast while it was away");
    }

    private static final class TestWebSocket extends WebSocket {

        private final WebSocketProcessor processor;
        private final boolean reportsCloseAtOnce;
        volatile boolean closeCalled;

        TestWebSocket(AtmosphereFramework framework, WebSocketProcessor processor, boolean reportsCloseAtOnce) {
            super(framework.getAtmosphereConfig());
            this.processor = processor;
            this.reportsCloseAtOnce = reportsCloseAtOnce;
        }

        @Override
        public boolean isOpen() {
            return !closeCalled;
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
            if (!closeCalled) {
                closeCalled = true;
                if (reportsCloseAtOnce) {
                    processor.close(this, 1000);
                }
            }
        }
    }
}
