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
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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

        pathBroadcaster = mock(Broadcaster.class);
        var broadcasterConfig = mock(BroadcasterConfig.class);
        when(pathBroadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(pathBroadcaster.getID()).thenReturn(PATH);

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

    private Future<?> send(Post post) {
        return posts.submit(() -> {
            handler.onRequest(post.resource());
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

        verify(pathBroadcaster).broadcast(eq("A's prompt"), eq(nextPollA));
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
        verify(pathBroadcaster).broadcast(eq("first"), eq(nextPoll));
        verify(pathBroadcaster, never()).broadcast(eq("second"), any(AtmosphereResource.class));
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
        verify(pathBroadcaster).broadcast(eq("A"), eq(nextPoll));
    }

    @Test
    void webSocketSuspendedUuidWaitsForTheSameConnectionId() throws Exception {
        poll("ws-A");
        pollCompleted("ws-A");

        var post = webSocketFrame("ws-A", "ws prompt");
        var pending = send(post);
        Thread.sleep(100);
        var reconnected = poll("ws-A");
        pending.get(5, TimeUnit.SECONDS);

        verify(pathBroadcaster, timeout(1_000)).broadcast(eq("ws prompt"), eq(reconnected));
        verify(pathBroadcaster, never()).broadcast(any());
    }

    @Test
    void liveConnectionIsDispatchedToWithoutWaiting() throws Exception {
        var pollA = poll("client-A");
        poll("client-B");

        var post = post("client-A", "live");
        send(post).get(5, TimeUnit.SECONDS);

        verify(pathBroadcaster).broadcast(eq("live"), eq(pollA));
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

    @AiEndpoint(path = PATH)
    static class StubEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            // Never invoked: the broadcaster is a mock, so a dispatched prompt
            // is observed on it rather than run.
        }
    }
}
