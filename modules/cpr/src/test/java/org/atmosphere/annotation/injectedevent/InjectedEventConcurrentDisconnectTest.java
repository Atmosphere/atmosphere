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
package org.atmosphere.annotation.injectedevent;

import jakarta.inject.Inject;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * One managed service instance serves every client of its path, and each
 * callback re-injects its own event into the shared {@code @Inject} field.
 * When several clients close in the same tick the field was overwritten by the
 * last injection, so every {@code @Disconnect} read the same client's event
 * and acted on the wrong user. Each callback must see its own event.
 */
class InjectedEventConcurrentDisconnectTest {

    private static final int CLIENTS = 3;

    /** The uuid each {@code @Disconnect} read from the injected event. */
    static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    static final List<String> READS = Collections.synchronizedList(new ArrayList<>());
    static volatile CyclicBarrier allInjected;

    @ManagedService(path = "/injected-room")
    public static final class RoomService {

        @Inject
        private AtmosphereResourceEvent event;

        @Disconnect
        public void onDisconnect() throws Exception {
            // Every closing client has had its event injected before any reads it.
            allInjected.await(5, TimeUnit.SECONDS);
            var uuid = event.getResource().uuid();
            SEEN.add(uuid);
            READS.add(uuid);
        }
    }

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() throws Exception {
        SEEN.clear();
        READS.clear();
        allInjected = new CyclicBarrier(CLIENTS);
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
    void concurrentDisconnectsEachSeeTheirOwnInjectedEvent() throws Exception {
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        var sockets = new ArrayList<WebSocket>();
        var expected = new HashSet<String>();
        for (int i = 0; i < CLIENTS; i++) {
            var webSocket = new NoopWebSocket(framework);
            AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                    .destroyable(false).pathInfo("/injected-room").build();
            processor.open(webSocket, request,
                    AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
            sockets.add(webSocket);
            expected.add(webSocket.resource().uuid());
        }

        // The clients drop in the same tick, each close on its own thread.
        var closers = new ArrayList<Thread>();
        for (var webSocket : sockets) {
            closers.add(Thread.ofPlatform().start(() -> processor.close(webSocket, 1006)));
        }
        for (var closer : closers) {
            closer.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(closer.isAlive(), "every close must have finished");
        }

        assertEquals(CLIENTS, READS.size(), "@Disconnect must run once per client");
        assertEquals(expected, SEEN, "each @Disconnect must read its own client's event");
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
