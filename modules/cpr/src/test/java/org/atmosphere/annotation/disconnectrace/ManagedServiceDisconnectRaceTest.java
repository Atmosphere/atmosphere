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
package org.atmosphere.annotation.disconnectrace;

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
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The broadcaster's closing pre-check is check-then-act: a write thread can
 * pass it, then the close thread cancels the shared event and runs
 * {@code @Disconnect}, and the write thread then hands the handler that
 * cancelled event, which {@code ManagedAtmosphereHandler} took for a second
 * {@code @Disconnect}. The sweep logged it 2-3 times per client and 19 times
 * on an AI sample. {@code @Disconnect} must fire once per resource whatever
 * the interleaving (Invariant #2).
 */
class ManagedServiceDisconnectRaceTest {

    private static final long WAIT_SECONDS = 10;

    /** {@code @Disconnect} calls, per resource uuid. */
    static final Map<String, AtomicInteger> DISCONNECTS = new ConcurrentHashMap<>();

    // Deterministic interleaving, see forcedInterleaving...
    static final AtomicBoolean HOOK_ARMED = new AtomicBoolean();
    static volatile CountDownLatch writerPastCheck;
    static volatile CountDownLatch closerInDisconnect;
    static volatile CountDownLatch writerDispatched;

    static void record(AtmosphereResourceEvent event) {
        DISCONNECTS.computeIfAbsent(event.getResource().uuid(), k -> new AtomicInteger()).incrementAndGet();
    }

    @ManagedService(path = "/disconnect-race")
    public static final class RaceService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            record(event);
        }
    }

    // Parks the thread that won the @Disconnect claim, then records whether the
    // resource was still whole when the callback went on.
    static volatile CountDownLatch holdInDisconnect;
    static volatile CountDownLatch inHeldDisconnect;
    static final Map<String, Boolean> RESOURCE_WHOLE = new ConcurrentHashMap<>();

    @ManagedService(path = "/disconnect-hold")
    public static final class HoldService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) throws InterruptedException {
            inHeldDisconnect.countDown();
            holdInDisconnect.await(WAIT_SECONDS, TimeUnit.SECONDS);
            var resource = event.getResource();
            RESOURCE_WHOLE.put(resource != null ? resource.uuid() : "torn-down", resource != null);
            if (resource != null) {
                record(event);
            }
        }
    }

    @ManagedService(path = "/disconnect-race-hook", broadcaster = GapBroadcaster.class)
    public static final class HookService {

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) throws InterruptedException {
            record(event);
            if (closerInDisconnect.getCount() > 0) {
                // The close thread is inside the first @Disconnect: the event is
                // cancelled but the resource not yet destroyed. Hold it here
                // until the writer has dispatched its broadcast.
                closerInDisconnect.countDown();
                writerDispatched.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Parks the write thread between the broadcaster's validity check and the
     * handler dispatch, exactly where the race lands.
     */
    public static final class GapBroadcaster extends DefaultBroadcaster {

        @Override
        protected void prepareInvokeOnStateChange(AtmosphereResource r, AtmosphereResourceEvent e) {
            if (!HOOK_ARMED.compareAndSet(true, false)) {
                super.prepareInvokeOnStateChange(r, e);
                return;
            }
            writerPastCheck.countDown();
            try {
                closerInDisconnect.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            try {
                super.prepareInvokeOnStateChange(r, e);
            } finally {
                writerDispatched.countDown();
            }
        }
    }

    private AtmosphereFramework framework;
    private WebSocketProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        DISCONNECTS.clear();
        RESOURCE_WHOLE.clear();
        holdInDisconnect = new CountDownLatch(1);
        inHeldDisconnect = new CountDownLatch(1);
        HOOK_ARMED.set(false);
        writerPastCheck = new CountDownLatch(1);
        closerInDisconnect = new CountDownLatch(1);
        writerDispatched = new CountDownLatch(1);
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(RaceService.class);
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
        processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
    }

    @AfterEach
    void tearDown() {
        // Never leave a parked thread behind a failed assertion.
        closerInDisconnect.countDown();
        writerDispatched.countDown();
        holdInDisconnect.countDown();
        framework.destroy();
    }

    private WebSocket open(String path) throws IOException {
        var webSocket = new NoopWebSocket(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo(path).build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        return webSocket;
    }

    private static int disconnects(String uuid) {
        var count = DISCONNECTS.get(uuid);
        return count == null ? 0 : count.get();
    }

    @Test
    void broadcastDispatchedAfterTheCloseCancelledTheEventDoesNotRunDisconnectAgain() throws Exception {
        var webSocket = open("/disconnect-race-hook");
        var resource = webSocket.resource();
        var uuid = resource.uuid();
        var broadcaster = resource.getBroadcaster();
        assertTrue(broadcaster instanceof GapBroadcaster, "precondition: the hook broadcaster is in use");

        HOOK_ARMED.set(true);
        broadcaster.broadcast("in-flight");
        assertTrue(writerPastCheck.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the write thread must have passed the validity check");

        // The close cancels the event and runs @Disconnect, which holds the
        // close until the parked write has dispatched the same event.
        processor.close(webSocket, 1000);

        assertTrue(closerInDisconnect.await(0, TimeUnit.SECONDS), "the close must have run @Disconnect");
        assertTrue(writerDispatched.await(WAIT_SECONDS, TimeUnit.SECONDS), "the write must have dispatched");

        assertEquals(1, disconnects(uuid), "@Disconnect must fire exactly once per resource");
    }

    @Test
    void theCloseDoesNotTearDownAResourceWhoseDisconnectAnotherThreadIsRunning() throws Exception {
        var webSocket = open("/disconnect-hold");
        var resource = (AtmosphereResourceImpl) webSocket.resource();
        var uuid = resource.uuid();
        var event = resource.getAtmosphereResourceEvent();
        event.isClosedByClient(true);

        // A broadcast thread delivers the closed event first: it wins the claim
        // and is still inside @Disconnect when the close tears the resource down.
        var broadcastThread = Thread.ofPlatform().start(() -> {
            try {
                resource.getAtmosphereHandler().onStateChange(event);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertTrue(inHeldDisconnect.await(WAIT_SECONDS, TimeUnit.SECONDS), "the broadcast thread runs @Disconnect");

        var closeThread = Thread.ofPlatform().start(() -> {
            try {
                resource.cancel();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
        closeThread.join(500);
        assertTrue(closeThread.isAlive(), "the close must wait for the @Disconnect another thread is running");

        holdInDisconnect.countDown();
        broadcastThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        closeThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertFalse(closeThread.isAlive(), "the close finishes once @Disconnect returned");
        assertEquals(Boolean.TRUE, RESOURCE_WHOLE.get(uuid), "@Disconnect must see its resource");
        assertEquals(1, disconnects(uuid), "@Disconnect must fire exactly once");
    }

    @Test
    void broadcastsRacingTheCloseRunDisconnectOncePerResource() throws Exception {
        List<String> uuids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            var webSocket = open("/disconnect-race");
            var resource = webSocket.resource();
            var uuid = resource.uuid();
            uuids.add(uuid);
            var broadcaster = resource.getBroadcaster();
            var start = new CyclicBarrier(2);
            var closed = new AtomicBoolean();

            var writer = Thread.ofPlatform().start(() -> {
                try {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    // Keep writes in flight across the whole close, and a few past it.
                    int after = 0;
                    while (after < 8) {
                        broadcaster.broadcast("m");
                        if (closed.get()) {
                            after++;
                        }
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });

            start.await(WAIT_SECONDS, TimeUnit.SECONDS);
            processor.close(webSocket, 1000);
            closed.set(true);
            writer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(writer.isAlive(), "the writer must have finished (iteration " + i + ")");
            // When a broadcast thread won the claim, its @Disconnect may still
            // be running: wait for the first call, never for a second one.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (disconnects(uuid) == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertEquals(1, disconnects(uuid), "@Disconnect must fire exactly once (iteration " + i + ")");
        }
        // A write still in flight when an iteration asserted lands by now.
        for (var uuid : uuids) {
            assertEquals(1, disconnects(uuid), "@Disconnect must fire exactly once for " + uuid);
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
