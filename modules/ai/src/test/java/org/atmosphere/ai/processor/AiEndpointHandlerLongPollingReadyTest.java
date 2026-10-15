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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.FrameworkConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code @Ready} runs once per long-polling client, as it does once per
 * WebSocket or SSE connection: a client's later polls continue its connection,
 * and the request attributes {@code @Ready} set on the first poll are carried to
 * them, but only to a poll of the same identity presenting the same request.
 */
class AiEndpointHandlerLongPollingReadyTest {

    private static final String PATH = "/atmosphere/ai";

    private final Map<String, String> initParams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtmosphereResource> registered = new ConcurrentHashMap<>();
    private AtmosphereConfig config;
    private Broadcaster broadcaster;
    private ReadyEndpoint endpoint;
    private AiEndpointHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        config = mock(AtmosphereConfig.class);
        var factory = mock(AtmosphereResourceFactory.class);
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(registered.get(inv.<String>getArgument(0))));
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());
        when(config.getInitParameter(anyString())).thenAnswer(inv -> initParams.get(inv.<String>getArgument(0)));
        broadcaster = mock(Broadcaster.class);
        var broadcasterConfig = mock(BroadcasterConfig.class);
        when(broadcasterConfig.filters()).thenReturn(List.of());
        when(broadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(broadcaster.getID()).thenReturn(PATH);
        endpoint = new ReadyEndpoint();
        var promptMethod = ReadyEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class);
        handler = new AiEndpointHandler(endpoint, promptMethod, 30_000L, "",
                mock(AgentRuntime.class), List.<AiInterceptor>of());
    }

    /** A request of one connection or poll, its attributes held in a map like a real one. */
    private record Conn(AtmosphereResourceImpl resource, Map<String, Object> attributes,
                        List<AtmosphereResourceEventListener> listeners) {

        /** The poll returned: resumed by a reply, a broadcast or its timeout. */
        void resumed() {
            when(resource.isSuspended()).thenReturn(false);
            var event = mock(AtmosphereResourceEvent.class);
            when(event.isResuming()).thenReturn(true);
            listeners.forEach(l -> l.onResume(event));
        }

        /** The client closed the connection, or it was cancelled. */
        void departed() {
            when(resource.isSuspended()).thenReturn(false);
            var event = mock(AtmosphereResourceEvent.class);
            when(event.isCancelled()).thenReturn(true);
            listeners.forEach(l -> l.onDisconnect(event));
        }
    }

    /**
     * A GET of {@code trackingId} on {@code transport}, presenting {@code token}
     * as a query parameter (none when null), signed in as {@code user}
     * (anonymous when null). Suspending registers it, as the framework does.
     */
    private Conn connect(AtmosphereResource.TRANSPORT transport, String trackingId, String token, String user)
            throws Exception {
        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        var attributes = Collections.synchronizedMap(new HashMap<String, Object>());
        var listeners = new CopyOnWriteArrayList<AtmosphereResourceEventListener>();
        when(request.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            String name = inv.getArgument(0);
            Object value = inv.getArgument(1);
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttributeNames()).thenAnswer(inv -> {
            synchronized (attributes) {
                return Collections.enumeration(new ArrayList<>(attributes.keySet()));
            }
        });
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn(PATH);
        if (token != null) {
            when(request.getParameter("token")).thenReturn(token);
            when(request.getParameterMap()).thenReturn(Map.of("token", new String[]{token}));
        }
        if (user != null) {
            attributes.put(FrameworkConfig.AUTH_PRINCIPAL, (Principal) () -> user);
        }
        when(resource.uuid()).thenReturn(trackingId);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.getResponse()).thenReturn(mock(AtmosphereResponse.class));
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.transport()).thenReturn(transport);
        when(resource.addEventListener(any())).thenAnswer(inv -> {
            listeners.add(inv.getArgument(0));
            return resource;
        });
        when(resource.suspend(anyLong())).thenAnswer(inv -> {
            when(resource.isSuspended()).thenReturn(true);
            registered.put(trackingId, resource);
            return resource;
        });
        var conn = new Conn(resource, attributes, listeners);
        handler.onRequest(resource);
        return conn;
    }

    private Conn poll(String trackingId, String token, String user) throws Exception {
        return connect(AtmosphereResource.TRANSPORT.LONG_POLLING, trackingId, token, user);
    }

    private static String newId() {
        return "lp-" + UUID.randomUUID();
    }

    @Test
    void readyRunsOnceForTheSuccessivePollsOfOneClient() throws Exception {
        var id = newId();
        for (int i = 0; i < 4; i++) {
            poll(id, "alice-token", null).resumed();
        }

        assertEquals(1, endpoint.readyCount(id),
                "@Ready must run for the client's first poll only, as for one WebSocket connection");
    }

    @Test
    void attributesReadySetReachTheClientsLaterPolls() throws Exception {
        var id = newId();
        poll(id, "alice-token", null).resumed();

        var second = poll(id, "alice-token", null);

        assertEquals(1, endpoint.readyCount(id));
        assertEquals("alice-token", second.attributes().get("ai.userId"),
                "a prompt dispatched to a later poll reads the identity @Ready set for the connection");
    }

    @Test
    void aPollPresentingAnotherRequestInheritsNothingAndRunsReady() throws Exception {
        var id = newId();
        poll(id, "alice-token", null).resumed();

        // Same tracking id, no token: not the request @Ready derived alice's identity from.
        var other = poll(id, null, null);

        assertEquals(2, endpoint.readyCount(id));
        assertNull(other.attributes().get("ai.userId"),
                "another request under the same tracking id must not inherit the identity @Ready set");
    }

    @Test
    void aPollOfAnotherIdentityInheritsNothingAndRunsReady() throws Exception {
        var id = newId();
        poll(id, "shared-token", "alice").resumed();

        var bobs = poll(id, "shared-token", "bob");

        assertEquals(2, endpoint.readyCount(id));
        assertEquals("shared-token", bobs.attributes().get("ai.userId"),
                "bob's poll ran @Ready itself");
    }

    @Test
    void aClientThatLeftRunsReadyAgainWhenItComesBack() throws Exception {
        var id = newId();
        poll(id, "alice-token", null).departed();

        poll(id, "alice-token", null);

        assertEquals(2, endpoint.readyCount(id));
    }

    @Test
    void aLateDepartureOfAReplacedPollLeavesTheClientConnected() throws Exception {
        var id = newId();
        var first = poll(id, "alice-token", null);
        first.resumed();
        poll(id, "alice-token", null);
        // The first poll's loss is noticed only now, after the second one was suspended.
        first.departed();

        poll(id, "alice-token", null);

        assertEquals(1, endpoint.readyCount(id));
    }

    @Test
    void aClientSilentPastTheDepartureGraceRunsReadyAgain() throws Exception {
        initParams.put(LongPollingClients.DEPARTURE_GRACE_MS_PARAM, "1");
        var id = newId();
        poll(id, "alice-token", null).resumed();
        Thread.sleep(50);

        poll(id, "alice-token", null);

        assertEquals(2, endpoint.readyCount(id));
    }

    @Test
    void aClientWhosePollIsStillSuspendedIsNotTakenForGone() throws Exception {
        initParams.put(LongPollingClients.DEPARTURE_GRACE_MS_PARAM, "1");
        var id = newId();
        poll(id, "alice-token", null);
        Thread.sleep(50);

        // A second poll while the first is still suspended (the client re-polled
        // before the server noticed the first one end).
        poll(id, "alice-token", null);

        assertEquals(1, endpoint.readyCount(id));
    }

    @Test
    void trackedClientsAreBounded() throws Exception {
        initParams.put(LongPollingClients.MAX_CLIENTS_PARAM, "2");
        var ids = List.of(newId(), newId(), newId());
        for (var id : ids) {
            poll(id, "t", null).resumed();
        }
        for (var id : ids) {
            poll(id, "t", null).resumed();
        }

        assertEquals(2, handler.longPollingClients(), "the tracked clients must stay within the bound");
        assertEquals(1, endpoint.readyCount(ids.get(0)));
        assertEquals(1, endpoint.readyCount(ids.get(1)));
        assertEquals(2, endpoint.readyCount(ids.get(2)),
                "a client past the bound is not tracked: each of its polls runs @Ready, as before");
    }

    @Test
    void aGoneClientMakesRoomInAFullSet() throws Exception {
        initParams.put(LongPollingClients.MAX_CLIENTS_PARAM, "1");
        var first = newId();
        poll(first, "t", null).departed();
        var second = newId();
        poll(second, "t", null).resumed();

        poll(second, "t", null);

        assertEquals(1, endpoint.readyCount(second));
        assertEquals(1, handler.longPollingClients());
    }

    @Test
    void everyWebSocketAndSseConnectionRunsReady() throws Exception {
        for (var transport : List.of(AtmosphereResource.TRANSPORT.WEBSOCKET, AtmosphereResource.TRANSPORT.SSE)) {
            var id = "c-" + transport + "-" + UUID.randomUUID();
            connect(transport, id, "alice-token", null).departed();
            connect(transport, id, "alice-token", null);

            assertEquals(2, endpoint.readyCount(id), transport + ": each connection runs @Ready");
        }
        assertEquals(0, handler.longPollingClients(), "only long-polling clients are tracked");
    }

    @Test
    void documentedInitParamsAndDefaultsAreTheOnesRead() {
        assertEquals("org.atmosphere.ai.longPolling.departureGraceMs", LongPollingClients.DEPARTURE_GRACE_MS_PARAM);
        assertEquals("org.atmosphere.ai.longPolling.maxTrackedClients", LongPollingClients.MAX_CLIENTS_PARAM);
        assertEquals(30_000L, LongPollingClients.DEFAULT_DEPARTURE_GRACE_MS);
        assertEquals(10_000, LongPollingClients.DEFAULT_MAX_CLIENTS);
    }

    @AiEndpoint(path = PATH)
    static class ReadyEndpoint {
        private final Map<String, Integer> ready = new ConcurrentHashMap<>();

        @Ready
        public void onReady(AtmosphereResource resource) {
            ready.merge(resource.uuid(), 1, Integer::sum);
            var request = resource.getRequest();
            var token = request.getParameter("token");
            if (request.getAttribute("ai.userId") == null && token != null) {
                request.setAttribute("ai.userId", token);
            }
        }

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }

        int readyCount(String trackingId) {
            return ready.getOrDefault(trackingId, 0);
        }
    }
}
