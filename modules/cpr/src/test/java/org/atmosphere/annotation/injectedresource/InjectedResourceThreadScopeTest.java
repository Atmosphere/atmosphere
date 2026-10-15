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
package org.atmosphere.annotation.injectedresource;

import jakarta.inject.Inject;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Ready;
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
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * One managed service instance serves every client of its path, and each
 * callback re-injects its own {@link AtmosphereResource}, {@link AtmosphereRequest}
 * and {@link AtmosphereResponse} into the shared {@code @Inject} fields. A field
 * that held the last injection made every concurrent callback act on that one
 * client: several clients closing in the same tick all ran their
 * {@code @Disconnect} for the same user. Each callback must read its own client,
 * whichever thread runs it: the closing thread, or a broadcast thread that
 * claimed the {@code @Disconnect} or delivers a message.
 */
class InjectedResourceThreadScopeTest {

    private static final int CLIENTS = 3;
    private static final String CLIENT_HEADER = "X-Test-Client";

    /** What one callback read through the injected fields. */
    record Read(String uuid, String requestClient, String responseClient) {
    }

    static final List<Read> READS = Collections.synchronizedList(new ArrayList<>());
    static final Queue<String> MESSAGE_READS = new ConcurrentLinkedQueue<>();
    static final Map<String, AtmosphereResource> CAPTURED = new ConcurrentHashMap<>();
    static final AtomicReference<CapturingService> CAPTURING = new AtomicReference<>();
    static final Map<String, AtmosphereResource> USERS = new ConcurrentHashMap<>();
    static final Set<AtmosphereResource> CONNECTED = ConcurrentHashMap.newKeySet();
    static final Queue<Lookup> LOOKUPS = new ConcurrentLinkedQueue<>();
    static volatile String lookedUp;
    static volatile CyclicBarrier allInjected;

    static Read read(AtmosphereResource r, AtmosphereRequest req, AtmosphereResponse res) {
        return new Read(r.uuid(), req.getHeader(CLIENT_HEADER), res.getHeader(CLIENT_HEADER));
    }

    @ManagedService(path = "/injected-resource-room")
    public static final class RoomService {

        @Inject
        private AtmosphereResource r;

        @Inject
        private AtmosphereRequest req;

        @Inject
        private AtmosphereResponse res;

        @Disconnect
        public void onDisconnect() throws Exception {
            // Every closing client has had its fields injected before any reads them.
            allInjected.await(5, TimeUnit.SECONDS);
            READS.add(read(r, req, res));
        }
    }

    @ManagedService(path = "/injected-resource-broadcast")
    public static final class BroadcastService {

        @Inject
        private AtmosphereResource r;

        @Inject
        private AtmosphereRequest req;

        @Inject
        private AtmosphereResponse res;

        @Disconnect
        public void onDisconnect() {
            READS.add(read(r, req, res));
        }

        @Message
        public String onMessage(String message) {
            MESSAGE_READS.add(r.uuid());
            return message;
        }
    }

    @ManagedService(path = "/injected-resource-capture")
    public static final class CapturingService {

        @Inject
        private AtmosphereResource r;

        @Inject
        private AtmosphereResourceImpl impl;

        @Ready
        public void onReady(AtmosphereResource resource) {
            CAPTURED.put(resource.uuid(), r);
            CAPTURING.set(this);
        }

        AtmosphereResourceImpl impl() {
            return impl;
        }
    }

    /** What a callback of {@code caller} reached through the reference another client stored. */
    record Lookup(String caller, String reached) {
    }

    /** Keeps each client's injected field, the way a chat keeps its connected users. */
    @ManagedService(path = "/injected-resource-users")
    public static final class UsersService {

        @Inject
        private AtmosphereResource r;

        @Ready
        public void onReady() {
            USERS.put(r.uuid(), r);
            CONNECTED.add(r);
        }

        @Message
        public String onMessage(String message) {
            LOOKUPS.add(new Lookup(r.uuid(), USERS.get(lookedUp).uuid()));
            return message;
        }

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            CONNECTED.remove(event.getResource());
        }
    }

    private AtmosphereFramework framework;
    private WebSocketProcessor processor;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        READS.clear();
        MESSAGE_READS.clear();
        CAPTURED.clear();
        CAPTURING.set(null);
        USERS.clear();
        CONNECTED.clear();
        LOOKUPS.clear();
        lookedUp = null;
        allInjected = new CyclicBarrier(CLIENTS);
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
        processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        framework.destroy();
    }

    /** Open a client whose request and response both carry {@code label}. */
    private RecordingWebSocket open(String path, String label) throws IOException {
        var webSocket = new RecordingWebSocket(framework);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .destroyable(false).pathInfo(path).headers(Map.of(CLIENT_HEADER, label)).build();
        processor.open(webSocket, request,
                AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
        var resource = webSocket.resource();
        assertNotNull(resource, "the WebSocket must be open");
        resource.getResponse().setHeader(CLIENT_HEADER, label);
        return webSocket;
    }

    @Test
    void concurrentDisconnectsEachSeeTheirOwnInjectedResourceRequestAndResponse() throws Exception {
        var sockets = new ArrayList<WebSocket>();
        var expected = new ArrayList<Read>();
        for (int i = 0; i < CLIENTS; i++) {
            var label = "client-" + i;
            var webSocket = open("/injected-resource-room", label);
            sockets.add(webSocket);
            expected.add(new Read(webSocket.resource().uuid(), label, label));
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
        assertEquals(new HashMap<>(byUuid(expected)), new HashMap<>(byUuid(READS)),
                "each @Disconnect must read its own client's resource, request and response");
    }

    @Test
    void aDisconnectClaimedByABroadcastThreadSeesItsOwnClient() throws Exception {
        var alice = open("/injected-resource-broadcast", "alice");
        // Bob connects after Alice: his is the latest injection into the shared fields.
        open("/injected-resource-broadcast", "bob");

        var resource = (AtmosphereResourceImpl) alice.resource();
        var event = resource.getAtmosphereResourceEvent();
        event.isClosedByClient(true);
        // A broadcast thread delivers Alice's closed event and wins the @Disconnect claim.
        var broadcastThread = Thread.ofPlatform().start(() -> {
            try {
                resource.getAtmosphereHandler().onStateChange(event);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
        broadcastThread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(broadcastThread.isAlive(), "the broadcast thread must have run @Disconnect");

        assertEquals(List.of(new Read(resource.uuid(), "alice", "alice")), List.copyOf(READS),
                "a @Disconnect run by a broadcast thread must read its own client");
    }

    @Test
    void aBroadcastCallsMessageWithEachClientsOwnResource() throws Exception {
        var expected = new ArrayList<String>();
        AtmosphereResource any = null;
        for (int i = 0; i < CLIENTS; i++) {
            var webSocket = open("/injected-resource-broadcast", "client-" + i);
            expected.add(webSocket.resource().uuid());
            any = webSocket.resource();
        }
        assertNotNull(any);

        any.getBroadcaster().broadcast("hello").get(10, TimeUnit.SECONDS);

        assertEquals(CLIENTS, MESSAGE_READS.size(), "@Message must run once per client");
        assertEquals(new HashSet<>(expected), new HashSet<>(MESSAGE_READS),
                "each delivery must read its own client's resource");
    }

    @Test
    void aReferenceHandedToAnotherThreadKeepsItsOwnClientAndReachesIt() throws Exception {
        var alice = open("/injected-resource-capture", "alice");
        var aliceUuid = alice.resource().uuid();
        // Bob connects after Alice's callback captured the field.
        open("/injected-resource-capture", "bob");

        var captured = CAPTURED.get(aliceUuid);
        assertNotNull(captured, "@Ready must have run for Alice");
        assertEquals(aliceUuid, pool.submit(captured::uuid).get(5, TimeUnit.SECONDS),
                "a reference used off the callback thread must act on its own client");

        // Handed back to the framework, the reference reaches Alice herself.
        captured.getBroadcaster().broadcast("direct", captured).get(10, TimeUnit.SECONDS);
        assertTrue(alice.written().contains("direct"), "a broadcast to the injected resource must reach it");
        assertTrue(captured.equals(alice.resource()), "the injected reference equals its resource");

        // A field of the implementation type cannot hold the per-thread proxy.
        var service = CAPTURING.get();
        assertNotNull(service);
        assertSame(alice.resource().getClass(), service.impl().getClass(),
                "an implementation-typed field receives the resource itself");
    }

    @Test
    void aReferenceAClientStoredActsOnThatClientInAnotherClientsCallback() throws Exception {
        var alice = open("/injected-resource-users", "alice");
        var bob = open("/injected-resource-users", "bob");
        var aliceUuid = alice.resource().uuid();
        var bobUuid = bob.resource().uuid();
        lookedUp = aliceUuid;

        // Bob's message looks up the reference Alice's @Ready stored.
        processor.invokeWebSocketProtocol(bob, "hi");

        assertEquals(List.of(new Lookup(bobUuid, aliceUuid)), List.copyOf(LOOKUPS),
                "a stored reference must act on the client it was injected for, not on the caller");
    }

    @Test
    void aBroadcastDeliveryReachesTheLatestClientThroughItsStoredReference() throws Exception {
        var alice = open("/injected-resource-users", "alice");
        // Bob connects last: his is the latest injection into the shared field.
        var bob = open("/injected-resource-users", "bob");
        var aliceUuid = alice.resource().uuid();
        var bobUuid = bob.resource().uuid();
        lookedUp = bobUuid;

        // Each delivery runs @Message on a broadcast thread, which injected nothing.
        alice.resource().getBroadcaster().broadcast("hello").get(10, TimeUnit.SECONDS);

        assertEquals(Set.of(new Lookup(aliceUuid, bobUuid), new Lookup(bobUuid, bobUuid)), Set.copyOf(LOOKUPS),
                "each delivery must read its own client, and Bob's stored reference must act on Bob");
    }

    @Test
    void aStoredReferenceIsRemovedWithTheResourceItWasInjectedFor() throws Exception {
        var alice = open("/injected-resource-users", "alice");
        var bob = open("/injected-resource-users", "bob");
        var aliceResource = alice.resource();
        assertEquals(2, CONNECTED.size(), "@Ready must have stored both clients");
        assertTrue(CONNECTED.contains(aliceResource), "the set finds a resource by the reference stored for it");
        assertTrue(aliceResource.equals(USERS.get(aliceResource.uuid())),
                "a resource equals the reference stored for it");

        processor.close(alice, 1000);

        assertEquals(Set.of(bob.resource().uuid()), uuids(CONNECTED),
                "@Disconnect must remove the departed client's stored reference");
    }

    private static Set<String> uuids(Set<AtmosphereResource> resources) {
        var uuids = new HashSet<String>();
        for (var resource : resources) {
            uuids.add(resource.uuid());
        }
        return uuids;
    }

    private static Map<String, Read> byUuid(List<Read> reads) {
        var map = new HashMap<String, Read>();
        synchronized (reads) {
            for (var read : reads) {
                map.put(read.uuid(), read);
            }
        }
        return map;
    }

    private static final class RecordingWebSocket extends WebSocket {

        private final StringBuffer written = new StringBuffer();

        RecordingWebSocket(AtmosphereFramework framework) {
            super(framework.getAtmosphereConfig());
        }

        String written() {
            return written.toString();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) {
            written.append(s);
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) {
            written.append(new String(b, offset, length, StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public void close() {
        }
    }
}
