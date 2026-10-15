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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.annotation.Command;
import org.atmosphere.agent.command.CommandRegistry;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.LongPollingReplies;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.cpr.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a slash command's answer goes, with the real {@link AiEndpointHandler}
 * behind the {@link AgentHandler}: to the connection of the client that sent
 * it, on every transport, never to the POST's own response (which an SSE or
 * long-polling client does not read) nor to the other subscribers of the path.
 * Over long-polling it is kept whole for the client's next poll, like an AI
 * reply. Each answer is a reply of its own, under a fresh session id.
 */
class AgentHandlerReplyDeliveryTest {

    private static final String PATH = "/atmosphere/agent/test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An agent whose commands count how often they ran. */
    public static class CountingAgent {
        final AtomicInteger runs = new AtomicInteger();

        @Command(value = "/ping", description = "Ping")
        public String ping() {
            runs.incrementAndGet();
            return "pong";
        }

        @Command(value = "/wipe", description = "Wipe", confirm = "Really wipe?")
        public String wipe() {
            runs.incrementAndGet();
            return "wiped";
        }
    }

    /** The endpoint behind the agent; its {@code @Prompt} never runs in these tests. */
    public static class Endpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.complete();
        }
    }

    private final ConcurrentHashMap<String, AtmosphereResource> registered = new ConcurrentHashMap<>();
    private final List<String> trackingIds = new ArrayList<>();
    private final ExecutorService posts = Executors.newCachedThreadPool();
    private AtmosphereConfig config;
    private Broadcaster broadcaster;
    private CountingAgent agent;
    private AgentHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        config = mock(AtmosphereConfig.class);
        var factory = mock(AtmosphereResourceFactory.class);
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(registered.get(inv.<String>getArgument(0))));
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());
        // A long-polling client posting between two polls is waited for this long.
        var initParams = Map.of("org.atmosphere.ai.prompt.repollWaitMs", "300");
        when(config.getInitParameter(anyString())).thenAnswer(inv -> initParams.get(inv.<String>getArgument(0)));
        broadcaster = mock(Broadcaster.class);
        var broadcasterConfig = mock(BroadcasterConfig.class);
        when(broadcasterConfig.filters()).thenReturn(List.of());
        when(broadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(broadcaster.getID()).thenReturn(PATH);

        var promptMethod = Endpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class);
        var aiHandler = new AiEndpointHandler(new Endpoint(), promptMethod, 30_000L, "",
                mock(AgentRuntime.class), List.<AiInterceptor>of());
        agent = new CountingAgent();
        var registry = new CommandRegistry();
        registry.scan(CountingAgent.class);
        handler = new AgentHandler(aiHandler, new CommandRouter(registry, agent), agent, null);
    }

    @AfterEach
    void tearDown() {
        posts.shutdownNow();
        trackingIds.forEach(LongPollingReplies::discard);
    }

    private String newTrackingId(String prefix) {
        var id = prefix + "-" + UUID.randomUUID();
        trackingIds.add(id);
        return id;
    }

    /**
     * A GET opening the client's connection, handled by the agent: suspended and
     * registered under {@code trackingId} as the framework does, unless the
     * agent answers it at once with a reply waiting for it. Every frame written
     * to it lands in {@code written}.
     */
    private AtmosphereResourceImpl connect(String trackingId, AtmosphereResource.TRANSPORT transport,
                                           List<String> written) throws Exception {
        var resource = mock(AtmosphereResourceImpl.class);
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().method("GET").build();
        when(resource.uuid()).thenReturn(trackingId);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.getResponse()).thenReturn(mock(AtmosphereResponse.class));
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.transport()).thenReturn(transport);
        var serializer = mock(Serializer.class);
        doAnswer(inv -> written.add(inv.getArgument(1))).when(serializer).write(any(), any());
        when(resource.getSerializer()).thenReturn(serializer);
        when(resource.suspend(anyLong())).thenAnswer(inv -> {
            when(resource.isSuspended()).thenReturn(true);
            registered.put(trackingId, resource);
            return resource;
        });
        handler.onRequest(resource);
        return resource;
    }

    /** The long-polling poll returned: the framework unregisters it. */
    private void pollReturned(AtmosphereResource poll) {
        when(poll.isSuspended()).thenReturn(false);
        when(poll.isResumed()).thenReturn(true);
        registered.remove(poll.uuid(), poll);
    }

    private record Post(AtmosphereResource resource, AtmosphereResponse response) {
    }

    /** An SSE or long-polling POST naming its connection with the tracking-id header. */
    private Post httpPost(String trackingId, AtmosphereResource.TRANSPORT transport, String body) {
        return post(trackingId, trackingId, transport, false, body);
    }

    /** A WebSocket frame: the server-set suspended uuid names its connection, no header. */
    private Post webSocketFrame(String suspendedUuid, String body) {
        return post(suspendedUuid, null, AtmosphereResource.TRANSPORT.WEBSOCKET, true, body);
    }

    private Post post(String suspendedUuid, String header, AtmosphereResource.TRANSPORT transport,
                      boolean webSocketFrame, String body) {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.uuid()).thenReturn(suspendedUuid);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.transport()).thenReturn(transport);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody(body));
        when(request.getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID)).thenReturn(suspendedUuid);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn(header);
        if (webSocketFrame) {
            when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn("true");
        }
        return new Post(resource, response);
    }

    private static JsonNode json(Object frame) {
        return MAPPER.readTree(frame.toString());
    }

    /** The frames broadcast to {@code connection} alone, in order. */
    private List<JsonNode> framesTo(AtmosphereResource connection) {
        var frames = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster, atLeastOnce()).broadcast(frames.capture(), eq(Set.of(connection)));
        return frames.getAllValues().stream()
                .map(frame -> json(((RawMessage) frame).message()))
                .toList();
    }

    /** One reply: a streaming-text frame carrying {@code text}, then complete, under one session id. */
    private static String assertReply(List<JsonNode> frames, String text) {
        assertEquals(2, frames.size(), "frames: " + frames);
        assertEquals("streaming-text", frames.get(0).get("type").asString(), "frames: " + frames);
        assertEquals(text, frames.get(0).get("data").asString(), "frames: " + frames);
        assertEquals("complete", frames.get(1).get("type").asString(), "frames: " + frames);
        var sessionId = frames.get(0).get("sessionId").asString();
        assertEquals(sessionId, frames.get(1).get("sessionId").asString(), "frames: " + frames);
        assertTrue(frames.get(0).get("seq").asLong() < frames.get(1).get("seq").asLong(), "frames: " + frames);
        return sessionId;
    }

    @Test
    void aCommandPostedOverSseIsAnsweredOnItsSendersStream() throws Exception {
        var id = newTrackingId("sse");
        var stream = connect(id, AtmosphereResource.TRANSPORT.SSE, new ArrayList<>());
        var other = connect(newTrackingId("sse"), AtmosphereResource.TRANSPORT.SSE, new ArrayList<>());
        var post = httpPost(id, AtmosphereResource.TRANSPORT.SSE, "/ping");

        handler.onRequest(post.resource());

        assertReply(framesTo(stream), "pong");
        verify(broadcaster, never()).broadcast(any(), eq(Set.of(other)));
        verify(broadcaster, never()).broadcast(any());
        verify(post.resource(), never()).write(anyString());
        assertEquals(1, agent.runs.get());
    }

    @Test
    void aCommandSentOverWebSocketIsAnsweredOnItsSocket() throws Exception {
        var id = newTrackingId("ws");
        var socket = connect(id, AtmosphereResource.TRANSPORT.WEBSOCKET, new ArrayList<>());
        var frame = webSocketFrame(id, "/ping");

        handler.onRequest(frame.resource());

        assertReply(framesTo(socket), "pong");
        verify(broadcaster, never()).broadcast(any());
        verify(frame.resource(), never()).write(anyString());
    }

    @Test
    void aCommandPostedOverLongPollingIsDeliveredWholeToTheNextPoll() throws Exception {
        var id = newTrackingId("lp");
        var poll = connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>());
        var post = httpPost(id, AtmosphereResource.TRANSPORT.LONG_POLLING, "/ping");

        handler.onRequest(post.resource());

        // The waiting poll is resumed empty so the client comes back for the reply.
        verify(poll).resume();
        verify(post.resource(), never()).write(anyString());
        verify(broadcaster, never()).broadcast(any());
        pollReturned(poll);

        var written = new ArrayList<String>();
        var next = connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, written);

        assertReply(written.stream().map(AgentHandlerReplyDeliveryTest::json).toList(), "pong");
        verify(next).setAction(Action.CANCELLED);
        verify(next, never()).suspend(anyLong());
    }

    @Test
    void eachCommandAnswerIsAReplyOfItsOwn() throws Exception {
        var id = newTrackingId("lp");
        var sessionIds = new ArrayList<String>();
        for (var i = 0; i < 2; i++) {
            // The client polls and waits, posts the command, and its next poll takes the answer.
            var poll = connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>());
            handler.onRequest(httpPost(id, AtmosphereResource.TRANSPORT.LONG_POLLING, "/ping").resource());
            pollReturned(poll);
            var written = new ArrayList<String>();
            connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, written);
            sessionIds.add(assertReply(written.stream().map(AgentHandlerReplyDeliveryTest::json).toList(), "pong"));
        }

        assertNotEquals(sessionIds.get(0), sessionIds.get(1),
                "a client dropping frames it already saw by (sessionId, seq) must take the second answer");
    }

    @Test
    void aConfirmationAndItsAnswerReachTheSendersConnection() throws Exception {
        var id = newTrackingId("sse");
        var stream = connect(id, AtmosphereResource.TRANSPORT.SSE, new ArrayList<>());

        handler.onRequest(httpPost(id, AtmosphereResource.TRANSPORT.SSE, "/wipe").resource());
        handler.onRequest(httpPost(id, AtmosphereResource.TRANSPORT.SSE, "yes").resource());

        var frames = framesTo(stream);
        var first = assertReply(frames.subList(0, 2), "Really wipe?");
        var second = assertReply(frames.subList(2, 4), "wiped");
        assertNotEquals(first, second);
        assertEquals(1, agent.runs.get());
        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void aCommandPostedBetweenTwoPollsRunsOnceTheNextPollArrives() throws Exception {
        var id = newTrackingId("lp");
        var poll = connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>());
        pollReturned(poll);
        var post = httpPost(id, AtmosphereResource.TRANSPORT.LONG_POLLING, "/ping");

        var pending = posts.submit(() -> {
            handler.onRequest(post.resource());
            return null;
        });
        var next = connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>());
        pending.get(5, TimeUnit.SECONDS);

        assertEquals(1, agent.runs.get());
        verify(next).resume();
        verify(post.response(), never()).setStatus(503);
        pollReturned(next);
        var written = new ArrayList<String>();
        connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, written);
        assertReply(written.stream().map(AgentHandlerReplyDeliveryTest::json).toList(), "pong");
    }

    @Test
    void aCommandWhoseConnectionDoesNotComeBackIsRefusedRetryablyWithoutRunning() throws Exception {
        var id = newTrackingId("lp");
        var poll = connect(id, AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>());
        pollReturned(poll);
        var post = httpPost(id, AtmosphereResource.TRANSPORT.LONG_POLLING, "/ping");

        handler.onRequest(post.resource());

        verify(post.response()).setStatus(503);
        verify(post.response()).setHeader("Retry-After", "1");
        assertEquals(0, agent.runs.get(), "a command refused for a retry must not have run");
        verify(post.resource(), never()).write(anyString());
        verify(broadcaster, never()).broadcast(any());
        assertFalse(LongPollingReplies.hasParked(id, owner -> true));
    }

    @Test
    void aCommandNamingNoConnectionIsRefusedWithoutRunning() throws Exception {
        // No tracking-id header: the framework then names the POST with an id of its own.
        var post = post("generated-" + UUID.randomUUID(), null, AtmosphereResource.TRANSPORT.LONG_POLLING,
                false, "/ping");

        handler.onRequest(post.resource());

        verify(post.response()).setStatus(400);
        assertEquals(0, agent.runs.get());
        verify(broadcaster, never()).broadcast(any());
    }
}
