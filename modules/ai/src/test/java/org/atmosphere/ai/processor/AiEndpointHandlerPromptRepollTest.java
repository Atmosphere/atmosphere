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
import org.atmosphere.ai.filter.AiStreamMessage;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A long-polling client that posts a prompt between two polls has no
 * registered connection: the previous poll was unregistered from the
 * resource factory when it completed, and the next has not arrived yet. The
 * prompt used to fan out to every subscriber of the path, so every other
 * client's {@code @Prompt} answered it. These tests pin that the prompt instead
 * waits for its own client's next poll, and is otherwise refused with a
 * retryable {@code 503} — never broadcast to all.
 */
class AiEndpointHandlerPromptRepollTest {

    private static final String PATH = "/atmosphere/ai";

    private final Map<String, AtmosphereResource> registered = new ConcurrentHashMap<>();
    private final ExecutorService posts = Executors.newCachedThreadPool();
    private AtmosphereConfig config;
    private AiEndpointHandler handler;
    /** The broadcaster every connection on the path shares: a fan-out would land here. */
    private Broadcaster pathBroadcaster;

    @BeforeEach
    void setUp() throws Exception {
        handler = newHandler(Map.of());
    }

    @AfterEach
    void tearDown() {
        posts.shutdownNow();
    }

    private AiEndpointHandler newHandler(Map<String, String> initParams) throws Exception {
        config = mock(AtmosphereConfig.class);
        var factory = mock(AtmosphereResourceFactory.class);
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(registered.get(inv.<String>getArgument(0))));
        when(config.getInitParameter(anyString()))
                .thenAnswer(inv -> initParams.get(inv.<String>getArgument(0)));
        // Like the real config: one map for the application, shared by every endpoint.
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());

        pathBroadcaster = mock(Broadcaster.class);
        var broadcasterConfig = mock(BroadcasterConfig.class);
        when(pathBroadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(pathBroadcaster.getID()).thenReturn(PATH);
        return endpointOnSameConfig();
    }

    /** Another @AiEndpoint handler of the same application (same config). */
    private AiEndpointHandler endpointOnSameConfig() throws Exception {
        var promptMethod = StubEndpoint.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
        return new AiEndpointHandler(new StubEndpoint(), promptMethod, 30_000L, "",
                mock(AgentRuntime.class), List.<AiInterceptor>of());
    }

    /** A long-polling GET that the handler suspends; registered like a real poll on suspend. */
    private AtmosphereResource poll(String trackingId) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.uuid()).thenReturn(trackingId);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(pathBroadcaster);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(resource.isSuspended()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        when(resource.suspend(30_000L)).thenAnswer(inv -> {
            registered.put(trackingId, resource);
            return resource;
        });
        handler.onRequest(resource);
        return resource;
    }

    /** The poll completed: like a real one, it is unregistered from the factory. */
    private void pollCompleted(String trackingId) {
        registered.remove(trackingId);
    }

    private record Post(AtmosphereResource resource, AtmosphereResponse response) {
    }

    /**
     * An SSE / long-polling prompt POST. Like the framework does, the request also
     * carries {@code SUSPENDED_ATMOSPHERE_RESOURCE_UUID}: the header's id, or a
     * freshly generated one when the client sent none.
     */
    private Post post(String trackingId, String prompt) {
        return request(trackingId, trackingId != null ? trackingId : "generated-" + System.nanoTime(),
                false, prompt);
    }

    /** A WebSocket frame: the server-set suspended uuid, no header. */
    private Post webSocketFrame(String suspendedUuid, String prompt) {
        return request(null, suspendedUuid, true, prompt);
    }

    private Post request(String trackingId, String attributeUuid, boolean webSocketFrame, String prompt) {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(pathBroadcaster);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody(prompt));
        when(request.getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID))
                .thenReturn(attributeUuid);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn(trackingId);
        if (webSocketFrame) {
            when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn("true");
        }
        return new Post(resource, response);
    }

    /** The carrier onRequest dispatches {@code text} in, addressed to {@code trackingId}. */
    private static AiEndpointHandler.PromptDispatch dispatched(String text, String trackingId) {
        return new AiEndpointHandler.PromptDispatch(text, trackingId);
    }

    private Future<?> send(Post post) {
        return send(handler, post);
    }

    private Future<?> send(AiEndpointHandler target, Post post) {
        return posts.submit(() -> {
            target.onRequest(post.resource());
            return null;
        });
    }

    @Test
    void promptPostedBetweenTwoPollsReachesOnlyItsSendersNextPoll() throws Exception {
        poll("client-A");
        var pollB = poll("client-B");
        pollCompleted("client-A");

        var post = post("client-A", "A's prompt");
        var pending = send(post);

        // The POST waits for A's next poll; nothing is dispatched meanwhile.
        Thread.sleep(200);
        assertFalse(pending.isDone(), "the prompt must wait for its sender's next poll");
        verify(pathBroadcaster, never()).broadcast(any());
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));

        var nextPollA = poll("client-A");
        pending.get(5, TimeUnit.SECONDS);

        verify(pathBroadcaster).broadcast(eq(dispatched("A's prompt", "client-A")), eq(nextPollA));
        verify(pathBroadcaster, never()).broadcast(any(), eq(pollB));
        verify(pathBroadcaster, never()).broadcast(any());
        verify(post.response(), never()).setStatus(503);
    }

    @Test
    void promptWhoseClientNeverPollsAgainIsRefusedWith503AfterTheWait() throws Exception {
        handler = newHandler(Map.of(PromptRepollGate.WAIT_MS_PARAM, "150"));
        poll("client-A");
        poll("client-B");
        pollCompleted("client-A");

        var post = post("client-A", "A's prompt");
        var started = System.nanoTime();
        send(post).get(5, TimeUnit.SECONDS);

        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(150),
                "the prompt must wait the configured time before it is refused");
        verify(post.response()).setStatus(503);
        verify(post.response()).setHeader("Retry-After", "1");
        verify(pathBroadcaster, never()).broadcast(any());
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    @Test
    void trackingIdThisEndpointNeverSuspendedIsRefusedWithoutWaiting() throws Exception {
        poll("client-B");

        var post = post("forged-id", "prompt");
        var started = System.nanoTime();
        send(post).get(5, TimeUnit.SECONDS);

        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000),
                "an id this endpoint never suspended must not hold a waiter");
        verify(post.response()).setStatus(503);
        verify(pathBroadcaster, never()).broadcast(any());
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    @Test
    void onlyOnePromptPerTrackingIdWaits() throws Exception {
        poll("client-A");
        pollCompleted("client-A");

        var first = post("client-A", "first");
        var pendingFirst = send(first);
        Thread.sleep(200);
        assertFalse(pendingFirst.isDone());

        var second = post("client-A", "second");
        send(second).get(5, TimeUnit.SECONDS);
        verify(second.response()).setStatus(503);

        var nextPoll = poll("client-A");
        pendingFirst.get(5, TimeUnit.SECONDS);
        verify(pathBroadcaster).broadcast(eq(dispatched("first", "client-A")), eq(nextPoll));
        verify(pathBroadcaster, never()).broadcast(eq(dispatched("second", "client-A")), any(AtmosphereResource.class));
    }

    @Test
    void waitersAreBoundedAcrossClients() throws Exception {
        handler = newHandler(Map.of(PromptRepollGate.MAX_WAITERS_PARAM, "1"));
        poll("client-A");
        poll("client-C");
        pollCompleted("client-A");
        pollCompleted("client-C");

        var waiting = post("client-A", "A");
        var pendingA = send(waiting);
        Thread.sleep(200);
        assertFalse(pendingA.isDone());

        var refused = post("client-C", "C");
        var started = System.nanoTime();
        send(refused).get(5, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000),
                "past the endpoint's waiter bound a prompt is refused at once");
        verify(refused.response()).setStatus(503);

        var nextPoll = poll("client-A");
        pendingA.get(5, TimeUnit.SECONDS);
        verify(pathBroadcaster).broadcast(eq(dispatched("A", "client-A")), eq(nextPoll));
    }

    @Test
    void waitersAreBoundedAcrossEndpointsOfTheApplication() throws Exception {
        // Each waiting prompt holds a request thread, so the bound is the
        // application's, not one per @AiEndpoint.
        var first = newHandler(Map.of(PromptRepollGate.MAX_WAITERS_PARAM, "1"));
        var second = endpointOnSameConfig();
        handler = first;
        poll("client-A");
        handler = second;
        poll("client-C");
        pollCompleted("client-A");
        pollCompleted("client-C");

        var pendingA = send(first, post("client-A", "A"));
        Thread.sleep(200);
        assertFalse(pendingA.isDone());

        var refused = post("client-C", "C");
        var started = System.nanoTime();
        send(second, refused).get(5, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000),
                "another endpoint's prompt is refused at once once the application's waiters are taken");
        verify(refused.response()).setStatus(503);

        handler = first;
        var nextPoll = poll("client-A");
        pendingA.get(5, TimeUnit.SECONDS);
        verify(pathBroadcaster).broadcast(eq(dispatched("A", "client-A")), eq(nextPoll));
    }

    @Test
    void destroyRefusesWaitingPromptsAtOnce() throws Exception {
        handler = newHandler(Map.of(PromptRepollGate.WAIT_MS_PARAM, "20000"));
        poll("client-A");
        pollCompleted("client-A");

        var waiting = post("client-A", "A");
        var pending = send(waiting);
        Thread.sleep(200);
        assertFalse(pending.isDone());

        var started = System.nanoTime();
        handler.destroy();
        pending.get(2, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000),
                "a stopping endpoint must not hold a waiting prompt until its wait runs out");
        verify(waiting.response()).setStatus(503);
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));

        // A prompt arriving after the endpoint stopped is refused without waiting.
        var late = post("client-A", "late");
        started = System.nanoTime();
        send(late).get(2, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000));
        verify(late.response()).setStatus(503);
    }

    @Test
    void webSocketFrameWithoutItsConnectionIsAnsweredWithAnErrorFrameAtOnce() throws Exception {
        // The frame's own socket is the connection: no later poll can take the
        // prompt, and a status is never seen by a WebSocket client. The refusal
        // goes back over the socket at once, never to another subscriber.
        var pollB = poll("client-B");
        poll("ws-A");
        pollCompleted("ws-A");

        var post = webSocketFrame("ws-A", "ws prompt");
        var started = System.nanoTime();
        send(post).get(5, TimeUnit.SECONDS);

        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000),
                "a WebSocket frame must not wait on the repoll gate");
        verify(post.resource()).write(argThat(AiEndpointHandlerPromptRepollTest::isRefusalFrame));
        verify(post.response(), never()).setStatus(anyInt());
        verify(pathBroadcaster, never()).broadcast(any());
        verify(pathBroadcaster, never()).broadcast(any(), eq(pollB));
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    @Test
    void liveConnectionIsDispatchedToWithoutWaiting() throws Exception {
        var pollA = poll("client-A");
        poll("client-B");

        var post = post("client-A", "live");
        send(post).get(5, TimeUnit.SECONDS);

        verify(pathBroadcaster).broadcast(eq(dispatched("live", "client-A")), eq(pollA));
        verify(pathBroadcaster, never()).broadcast(any());
        verify(post.response(), never()).setStatus(anyInt());
    }

    @Test
    void httpPromptWithoutTrackingIdIsRejectedWith400EvenWithTheGeneratedAttribute() throws Exception {
        poll("client-B");
        // The framework generated an id for this request and stored it as the
        // suspended uuid; that attribute identifies nobody on an HTTP POST.
        var post = post(null, "anonymous");
        send(post).get(5, TimeUnit.SECONDS);

        verify(post.response()).setStatus(400);
        verify(pathBroadcaster, never()).broadcast(any());
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    @Test
    void malformedTrackingIdIsRejectedWith400WithoutWaiting() throws Exception {
        poll("client-B");
        for (var malformed : List.of("../x", "a b", "id;drop", "x".repeat(129))) {
            var post = post(malformed, "prompt");
            send(post).get(5, TimeUnit.SECONDS);

            verify(post.response()).setStatus(400);
            verify(post.response(), never()).setStatus(503);
            verify(post.response(), never()).setHeader(eq("Retry-After"), anyString());
        }
        verify(pathBroadcaster, never()).broadcast(any());
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    @Test
    void wellFormedTrackingIdsFollowTheFrameworkRule() {
        assertTrue(AiEndpointHandler.isWellFormedTrackingId("Abc-123_x"));
        assertTrue(AiEndpointHandler.isWellFormedTrackingId("x".repeat(128)));
        assertFalse(AiEndpointHandler.isWellFormedTrackingId("x".repeat(129)));
        assertFalse(AiEndpointHandler.isWellFormedTrackingId(""));
        assertFalse(AiEndpointHandler.isWellFormedTrackingId("a/b"));
        assertFalse(AiEndpointHandler.isWellFormedTrackingId("a.b"));
    }

    /** A long-polling GET as the default interceptors see it before JavaScriptProtocol ends it. */
    private AtmosphereResource longPollingGet(String headerTrackingId, String serverUuid, boolean protocol) {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.uuid()).thenReturn(serverUuid);
        when(resource.getRequest()).thenReturn(request);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn(headerTrackingId);
        when(request.getHeader(HeaderConfig.X_ATMO_PROTOCOL)).thenReturn(protocol ? "true" : null);
        return resource;
    }

    @Test
    void promptPostedRightAfterALongPollingHandshakeWaitsForTheFirstPoll() throws Exception {
        var pollB = poll("client-B");
        // The handshake never reaches onRequest: JavaScriptProtocol answers it with
        // the server-assigned id and ends it. Only the recorder sees it.
        var action = handler.protocolHandshakeRecorder().inspect(longPollingGet("0", "server-id", true));
        assertEquals(Action.TYPE.CONTINUE, action.type());

        var post = post("server-id", "first prompt");
        var pending = send(post);
        Thread.sleep(200);
        assertFalse(pending.isDone(), "the prompt must wait for the first poll after the handshake");

        var firstPoll = poll("server-id");
        pending.get(5, TimeUnit.SECONDS);

        verify(pathBroadcaster).broadcast(eq(dispatched("first prompt", "server-id")), eq(firstPoll));
        verify(pathBroadcaster, never()).broadcast(any(), eq(pollB));
        verify(pathBroadcaster, never()).broadcast(any());
        verify(post.response(), never()).setStatus(anyInt());
    }

    @Test
    void handshakeRecorderIgnoresRequestsThatAreNotAHandshake() throws Exception {
        var recorder = handler.protocolHandshakeRecorder();
        // A client-chosen id, and a "0" without the protocol, are not ids the server assigned.
        recorder.inspect(longPollingGet("client-chosen", "client-chosen", true));
        recorder.inspect(longPollingGet("0", "generated-without-protocol", false));

        for (var id : List.of("client-chosen", "generated-without-protocol")) {
            var post = post(id, "prompt");
            var started = System.nanoTime();
            send(post).get(5, TimeUnit.SECONDS);
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000),
                    id + " was never assigned by the server: no waiter");
            verify(post.response()).setStatus(503);
        }
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    @Test
    void handshakeAssignedIdIsWaitedForOnlyWithinTheWait() throws Exception {
        // A handshake suspends nothing: its id buys one short wait for the first
        // poll, not the whole suspend window a real connection's id is kept for.
        when(config.getInitParameter(PromptRepollGate.WAIT_MS_PARAM)).thenReturn("50");
        var gate = new PromptRepollGate(120_000L);
        assertEquals(50L, gate.waitMs(config));
        gate.idAssigned("handshake-only");

        assertEquals(PromptRepollGate.Refusal.TIMEOUT,
                gate.await("handshake-only", System.nanoTime(), () -> null, config).refusal(),
                "a prompt posted right after the handshake waits for the first poll");
        Thread.sleep(150);
        assertEquals(PromptRepollGate.Refusal.UNKNOWN,
                gate.await("handshake-only", System.nanoTime(), () -> null, config).refusal(),
                "a handshake id no connection followed is forgotten after the wait");
    }

    @Test
    void handshakeIdsDoNotCrowdConnectionIdsOut() {
        // Handshakes are cheap and unauthenticated: a flood of them must not fill
        // the set real connections are recorded in.
        var gate = new PromptRepollGate(120_000L, 2, 2);
        for (var i = 0; i < 10; i++) {
            gate.idAssigned("handshake-" + i);
        }
        assertEquals(2, gate.assignedIds(), "the handshake-id set must not grow past its bound");
        assertEquals(0, gate.knownIds(), "handshake ids are not connection ids");

        gate.connectionReady("real-a");
        gate.connectionReady("real-b");
        assertEquals(2, gate.knownIds(), "a real connection's id must still be recorded");
        assertEquals(PromptRepollGate.Refusal.TIMEOUT,
                gate.await("real-a", System.nanoTime(), () -> null, null).refusal(),
                "a real connection's id is waited for");
    }

    @Test
    void connectionOfAHandshakeIdMovesItToTheKnownSet() {
        var gate = new PromptRepollGate(120_000L);
        gate.idAssigned("promoted");
        assertEquals(1, gate.assignedIds());

        gate.connectionReady("promoted");
        assertEquals(0, gate.assignedIds());
        assertEquals(1, gate.knownIds());
    }

    @Test
    void knownTrackingIdsAreBounded() {
        var gate = new PromptRepollGate(30_000L, 2);
        gate.connectionReady("a");
        gate.connectionReady("b");
        gate.connectionReady("c");
        assertEquals(2, gate.knownIds(), "the known-id set must not grow past its bound");
        // An id already known is refreshed, not refused.
        gate.connectionReady("a");
        assertEquals(2, gate.knownIds());
    }

    @Test
    void onlyARecentlySeenTrackingIdIsWaitedFor() throws Exception {
        // A 1 ms suspend window and a 1 ms wait: an id is remembered for about 2 ms.
        when(config.getInitParameter(PromptRepollGate.WAIT_MS_PARAM)).thenReturn("1");
        var gate = new PromptRepollGate(1L);
        gate.connectionReady("fresh");
        gate.connectionReady("stale");
        Thread.sleep(50);
        gate.connectionReady("fresh");

        assertEquals(PromptRepollGate.Refusal.TIMEOUT,
                gate.await("fresh", System.nanoTime(), () -> null, config).refusal(),
                "a recently seen id is waited for");
        assertEquals(PromptRepollGate.Refusal.UNKNOWN,
                gate.await("stale", System.nanoTime(), () -> null, config).refusal(),
                "an id last seen longer ago than the suspend window plus the wait is refused at once");
    }

    @Test
    void expiredTrackingIdsMakeRoomInAFullKnownIdSet() throws Exception {
        when(config.getInitParameter(PromptRepollGate.WAIT_MS_PARAM)).thenReturn("1");
        var gate = new PromptRepollGate(1L, 2);
        // Fix the wait (1 ms) so expiry is suspend window + 1 ms.
        assertEquals(1L, gate.waitMs(config));
        gate.connectionReady("a");
        gate.connectionReady("b");
        assertEquals(2, gate.knownIds());
        Thread.sleep(50);

        // Full, but every known id expired: the sweep makes room for the new one.
        gate.connectionReady("c");
        assertEquals(1, gate.knownIds(), "expired ids must be swept out of a full set");
        assertEquals(PromptRepollGate.Refusal.TIMEOUT,
                gate.await("c", System.nanoTime(), () -> null, config).refusal(),
                "the new id must be recorded, not refused as unknown");
    }

    @Test
    void repollWaitIsCappedAtThirtySeconds() {
        when(config.getInitParameter(PromptRepollGate.WAIT_MS_PARAM)).thenReturn("600000");
        assertEquals(30_000L, new PromptRepollGate(30_000L).waitMs(config),
                "the documented cap (README): a waiting prompt holds a request thread at most 30 s");
    }

    @Test
    void waiterIsReleasedOnEveryOutcome() throws Exception {
        when(config.getInitParameter(PromptRepollGate.WAIT_MS_PARAM)).thenReturn("50");
        var gate = new PromptRepollGate(30_000L);
        gate.connectionReady("a");
        var outcome = gate.await("a", System.nanoTime(), () -> null, config);
        assertEquals(PromptRepollGate.Refusal.TIMEOUT, outcome.refusal());
        assertEquals(0, gate.waiting(), "a timed-out waiter must not keep its tracking id's slot");

        var entered = new CountDownLatch(1);
        var waiter = new Thread(() -> {
            entered.countDown();
            gate.await("a", System.nanoTime(), () -> null, config);
        });
        waiter.start();
        entered.await();
        Thread.sleep(20);
        waiter.interrupt();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertEquals(0, gate.waiting(), "an interrupted waiter must not keep its tracking id's slot");
        // The same id can wait again: no slot leaked by the interrupted waiter.
        var again = gate.await("a", System.nanoTime(), () -> null, config);
        assertEquals(PromptRepollGate.Refusal.TIMEOUT, again.refusal());
    }

    /** The waiter semaphore every endpoint of the application shares, once a gate created it. */
    private Semaphore sharedWaiterSlots() {
        return (Semaphore) config.properties().get(PromptRepollGate.WAITER_SLOTS_PROPERTY);
    }

    @Test
    void everyOutcomeGivesTheSharedWaiterSlotBack() throws Exception {
        // A slot that is not given back is gone until restart: after
        // maxRepollWaiters waits in the application's life every later prompt
        // would be refused BUSY.
        when(config.getInitParameter(PromptRepollGate.MAX_WAITERS_PARAM)).thenReturn("2");
        when(config.getInitParameter(PromptRepollGate.WAIT_MS_PARAM)).thenReturn("300");
        var gate = new PromptRepollGate(30_000L);
        gate.connectionReady("a");

        // Timed out.
        assertEquals(PromptRepollGate.Refusal.TIMEOUT,
                gate.await("a", System.nanoTime(), () -> null, config).refusal());
        assertEquals(2, sharedWaiterSlots().availablePermits(), "a timed-out prompt must give its slot back");

        // Dispatched: a connection of the id was made ready after the prompt failed to resolve.
        var since = System.nanoTime();
        gate.connectionReady("a");
        var target = mock(AtmosphereResource.class);
        assertEquals(target, gate.await("a", since, () -> target, config).target());
        assertEquals(2, sharedWaiterSlots().availablePermits(), "a dispatched prompt must give its slot back");

        // Interrupted while waiting.
        var entered = new CountDownLatch(1);
        var interrupted = new Thread(() -> {
            entered.countDown();
            gate.await("a", System.nanoTime(), () -> null, config);
        });
        interrupted.start();
        entered.await();
        Thread.sleep(50);
        interrupted.interrupt();
        interrupted.join(5_000);
        assertFalse(interrupted.isAlive());
        assertEquals(2, sharedWaiterSlots().availablePermits(), "an interrupted prompt must give its slot back");

        // Refused BUSY because a prompt of the same id already waits: it took a
        // slot before it found the id taken.
        var waiting = posts.submit(() -> gate.await("a", System.nanoTime(), () -> null, config));
        Thread.sleep(50);
        assertEquals(PromptRepollGate.Refusal.BUSY,
                gate.await("a", System.nanoTime(), () -> null, config).refusal());
        assertEquals(1, sharedWaiterSlots().availablePermits(),
                "a prompt refused for a duplicate id must give its slot back; only the waiting one holds one");

        // Refused at shutdown while waiting.
        gate.shutdown();
        assertEquals(PromptRepollGate.Refusal.SHUTDOWN, waiting.get(5, TimeUnit.SECONDS).refusal());
        assertEquals(2, sharedWaiterSlots().availablePermits(), "a prompt refused at shutdown must give its slot back");
    }

    @Test
    void documentedDefaultsApplyWhenNothingIsConfigured() {
        // modules/ai/README.md: repollWaitMs 2000, maxRepollWaiters 64.
        var gate = new PromptRepollGate(30_000L);
        assertEquals(2_000L, gate.waitMs(config));
        assertEquals(64, sharedWaiterSlots().availablePermits());
    }

    @Test
    void documentedInitParamNamesAreTheOnesRead() {
        // The literal keys of the README table, not the constants: renaming a key
        // must fail here until the README is updated.
        when(config.getInitParameter("org.atmosphere.ai.prompt.repollWaitMs")).thenReturn("75");
        when(config.getInitParameter("org.atmosphere.ai.prompt.maxRepollWaiters")).thenReturn("3");
        var gate = new PromptRepollGate(30_000L);
        assertEquals(75L, gate.waitMs(config));
        assertEquals(3, sharedWaiterSlots().availablePermits());
    }

    @Test
    void handshakeOnlyIdsCannotTakeEveryWaiterSlot() throws Exception {
        // A handshake costs one unauthenticated request and names an id no poll
        // follows: handshake-and-post pairs must not leave a long-polling client
        // that is between two polls without a slot.
        handler = newHandler(Map.of(PromptRepollGate.MAX_WAITERS_PARAM, "4",
                PromptRepollGate.WAIT_MS_PARAM, "20000"));
        var recorder = handler.protocolHandshakeRecorder();
        var flood = new ArrayList<Post>();
        var pendingFlood = new ArrayList<Future<?>>();
        for (var i = 0; i < 4; i++) {
            recorder.inspect(longPollingGet("0", "handshake-" + i, true));
            var post = post("handshake-" + i, "flood " + i);
            flood.add(post);
            pendingFlood.add(send(post));
        }
        Thread.sleep(300);
        assertEquals(PromptRepollGate.handshakeShare(4),
                pendingFlood.stream().filter(f -> !f.isDone()).count(),
                "handshake-only ids may hold half of the slots, no more");
        for (var i = 0; i < 4; i++) {
            if (pendingFlood.get(i).isDone()) {
                verify(flood.get(i).response()).setStatus(503);
            }
        }

        // A real client between two polls still gets a slot and its prompt.
        poll("client-A");
        pollCompleted("client-A");
        var legit = post("client-A", "A's prompt");
        var pendingLegit = send(legit);
        Thread.sleep(200);
        assertFalse(pendingLegit.isDone(), "the between-poll prompt must wait, not be refused BUSY");
        var nextPoll = poll("client-A");
        pendingLegit.get(5, TimeUnit.SECONDS);
        verify(pathBroadcaster).broadcast(eq(dispatched("A's prompt", "client-A")), eq(nextPoll));
        verify(legit.response(), never()).setStatus(anyInt());

        handler.destroy();
        for (var f : pendingFlood) {
            f.get(5, TimeUnit.SECONDS);
        }
        assertEquals(4, sharedWaiterSlots().availablePermits(), "every slot is given back");
        assertEquals(PromptRepollGate.handshakeShare(4),
                ((Semaphore) config.properties().get(PromptRepollGate.HANDSHAKE_SLOTS_PROPERTY)).availablePermits(),
                "every handshake share slot is given back");
    }

    @Test
    void handshakeShareIsHalfTheSlotsAndAtLeastOne() {
        assertEquals(32, PromptRepollGate.handshakeShare(64));
        assertEquals(2, PromptRepollGate.handshakeShare(5));
        assertEquals(1, PromptRepollGate.handshakeShare(2));
        assertEquals(1, PromptRepollGate.handshakeShare(1));
    }

    @Test
    void closedConnectionsIdIsNotWaitedFor() throws Exception {
        // The id of a connection its client closed (or that was cancelled) is never
        // polled again: a prompt naming it is refused at once instead of holding a
        // slot for the whole wait.
        handler = newHandler(Map.of(PromptRepollGate.WAIT_MS_PARAM, "20000"));
        var pollA = poll("client-A");
        pollCompleted("client-A");
        handler.onStateChange(closedByClient(pollA));

        var post = post("client-A", "after close");
        var started = System.nanoTime();
        send(post).get(5, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000),
                "a closed connection's id must not hold a waiter");
        verify(post.response()).setStatus(503);
        verify(pathBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));

        // Coming back under the same id makes it known again.
        poll("client-A");
        pollCompleted("client-A");
        var again = send(post("client-A", "after reconnect"));
        Thread.sleep(200);
        assertFalse(again.isDone(), "a reconnected id is waited for again");
        var nextPoll = poll("client-A");
        again.get(5, TimeUnit.SECONDS);
        verify(pathBroadcaster).broadcast(eq(dispatched("after reconnect", "client-A")), eq(nextPoll));
    }

    private static AtmosphereResourceEvent closedByClient(AtmosphereResource resource) {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isClosedByClient()).thenReturn(true);
        return event;
    }

    @AiEndpoint(path = PATH)
    static class StubEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            // Never invoked: the broadcaster is a mock, so a dispatched prompt
            // is observed on it rather than run.
        }
    }

    /**
     * Whether {@code frame} is the WebSocket refusal: a terminal streaming-protocol
     * error that names a session of its own, as atmosphere.js
     * {@code subscribeStreaming} requires before it reports a frame at all.
     */
    static boolean isRefusalFrame(String frame) {
        try {
            var msg = AiStreamMessage.parse(frame);
            return msg != null && msg.isError()
                    && AiEndpointHandler.WEBSOCKET_REFUSAL_MESSAGE.equals(msg.data())
                    && msg.sessionId() != null && !msg.sessionId().isEmpty()
                    && msg.seq() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void refusalFrameNamesASessionSoSubscribeStreamingReportsIt() {
        // The exact frame atmosphere.js tests/unit/websocket-refusal-frame.test.ts feeds to
        // subscribeStreaming: a frame without sessionId is dropped by its decoder.
        assertEquals("{\"type\":\"error\",\"data\":\"" + AiEndpointHandler.WEBSOCKET_REFUSAL_MESSAGE
                        + "\",\"sessionId\":\"s-1\",\"seq\":1}",
                AiEndpointHandler.webSocketRefusalFrame("s-1"));
        assertEquals("Prompt not delivered: this connection is no longer registered on the server;"
                + " reconnect and send it again", AiEndpointHandler.WEBSOCKET_REFUSAL_MESSAGE);
        assertTrue(isRefusalFrame(AiEndpointHandler.webSocketRefusalFrame("s-1")));
        assertFalse(isRefusalFrame("{\"type\":\"error\",\"data\":\""
                + AiEndpointHandler.WEBSOCKET_REFUSAL_MESSAGE + "\"}"),
                "a session-less frame is not one subscribeStreaming reports");
    }
}
