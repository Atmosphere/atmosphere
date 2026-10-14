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
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * The injected {@link AtmosphereResourceEvent} of a shared managed service
 * resolves per calling thread while a callback runs. Outside a callback it must
 * stay the event it was injected for: a reference a callback hands to another
 * thread keeps acting on its own client, and a pooled thread that ran a
 * callback earlier does not keep serving that client's event afterwards.
 */
class InjectedEventThreadScopeTest {

    /** The injected event each client's {@code @Ready} read, by client uuid. */
    static final Map<String, AtmosphereResourceEvent> CAPTURED = new ConcurrentHashMap<>();
    static final AtomicReference<RoomService> SERVICE = new AtomicReference<>();

    @ManagedService(path = "/thread-scoped-room")
    public static final class RoomService {

        @Inject
        private AtmosphereResourceEvent event;

        @Ready
        public void onReady(AtmosphereResource resource) {
            CAPTURED.put(resource.uuid(), event);
            SERVICE.set(this);
        }

        AtmosphereResourceEvent event() {
            return event;
        }
    }

    private AtmosphereFramework framework;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        CAPTURED.clear();
        SERVICE.set(null);
        pool = Executors.newSingleThreadExecutor();
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
        pool.shutdownNow();
        framework.destroy();
    }

    private String open() throws IOException {
        var webSocket = new NoopWebSocket(framework);
        var processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo("/thread-scoped-room").build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        var resource = webSocket.resource();
        assertNotNull(resource, "the WebSocket must be open");
        return resource.uuid();
    }

    @Test
    void referenceHandedToAnotherThreadKeepsItsOwnClient() throws Exception {
        var alice = open();
        // A second client connects after Alice's callback captured the field.
        var bob = open();

        var captured = CAPTURED.get(alice);
        assertNotNull(captured, "@Ready must have run for Alice");
        var seen = pool.submit(() -> captured.getResource().uuid()).get(5, TimeUnit.SECONDS);

        assertEquals(alice, seen, "a reference used off the callback thread must act on its own client");
        assertNotNull(bob);
    }

    @Test
    void pooledThreadDoesNotKeepTheEventOfAnEarlierCallback() throws Exception {
        // Alice's callback runs on the pooled thread.
        var alice = pool.submit(this::open).get(5, TimeUnit.SECONDS);
        // Bob's runs elsewhere, and is the latest injection into the shared field.
        var bob = open();

        var service = SERVICE.get();
        assertNotNull(service, "@Ready must have run");
        // A later task on the same pooled thread, without an injection of its own.
        var seen = pool.submit(() -> service.event().getResource().uuid()).get(5, TimeUnit.SECONDS);

        assertEquals(bob, seen, "the pooled thread must not keep serving Alice's event (" + alice + ")");
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
