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
import org.atmosphere.ai.LongPollingReplies;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The poll side of a long-polling AI reply: a poll that finds its client's
 * reply parked is answered with it and not suspended; any other poll is
 * suspended, and resumed at once only when a reply for its own identity got
 * parked meanwhile; and none of them replays the client's run while its reply
 * is running or waiting.
 */
class AiEndpointHandlerLongPollingReplyTest {

    private static final String PATH = "/atmosphere/ai";

    private AtmosphereConfig config;
    private AiEndpointHandler handler;
    private Broadcaster broadcaster;
    private final ConcurrentHashMap<String, AtmosphereResource> registered = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        config = mock(AtmosphereConfig.class);
        var factory = mock(AtmosphereResourceFactory.class);
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(registered.get(inv.<String>getArgument(0))));
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());
        broadcaster = mock(Broadcaster.class);
        var broadcasterConfig = mock(BroadcasterConfig.class);
        when(broadcasterConfig.filters()).thenReturn(List.of());
        when(broadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(broadcaster.getID()).thenReturn(PATH);
        var promptMethod = StubEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class);
        handler = new AiEndpointHandler(new StubEndpoint(), promptMethod, 30_000L, "",
                mock(AgentRuntime.class), List.<AiInterceptor>of());
    }

    /** A long-polling GET of {@code trackingId} for {@code user} (anonymous when null), not handled yet. */
    private AtmosphereResourceImpl poll(String trackingId, String user, List<String> written) throws Exception {
        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.uuid()).thenReturn(trackingId);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.getResponse()).thenReturn(mock(AtmosphereResponse.class));
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(request.getMethod()).thenReturn("GET");
        if (user != null) {
            when(request.getAttribute(FrameworkConfig.AUTH_PRINCIPAL)).thenReturn((Principal) () -> user);
        }
        var serializer = mock(Serializer.class);
        doAnswer(inv -> written.add(inv.getArgument(1))).when(serializer).write(any(), any());
        when(resource.getSerializer()).thenReturn(serializer);
        return resource;
    }

    /** Suspending registers the poll, as the framework does. */
    private void suspendRegisters(AtmosphereResourceImpl poll) {
        when(poll.suspend(anyLong())).thenAnswer(inv -> {
            when(poll.isSuspended()).thenReturn(true);
            registered.put(poll.uuid(), poll);
            return poll;
        });
    }

    /** A reply of {@code text} asked on {@code trackingId}'s connection under {@code owner}, now ended. */
    private void replyEnded(String trackingId, Object owner, String text) {
        var asked = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(request.getAttribute(AiEndpointHandler.CONNECTION_OWNER_ATTRIBUTE)).thenReturn(owner);
        when(asked.uuid()).thenReturn(trackingId);
        when(asked.getRequest()).thenReturn(request);
        when(asked.getAtmosphereConfig()).thenReturn(config);
        when(asked.getBroadcaster()).thenReturn(broadcaster);
        when(asked.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        var session = StreamingSessions.start(asked);
        session.send(text);
        session.complete();
    }

    @Test
    void aPollFindingItsReplyIsAnsweredWithItAndNotSuspended() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        replyEnded(id, null, "hello");
        var written = new ArrayList<String>();
        var poll = poll(id, null, written);
        suspendRegisters(poll);

        handler.onRequest(poll);

        assertEquals(2, written.size(), "the streamed text and the complete frame, each written on its own");
        assertTrue(written.get(0).contains("\"hello\""));
        assertTrue(written.get(1).contains("\"complete\""));
        verify(poll).setAction(Action.CANCELLED);
        verify(poll, never()).suspend(anyLong());
    }

    @Test
    void aPollOfAnotherIdentityIsSuspendedAndNotResumedForTheReply() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        replyEnded(id, "alice", "for alice");
        var written = new ArrayList<String>();
        var poll = poll(id, "mallory", written);
        suspendRegisters(poll);

        handler.onRequest(poll);

        assertTrue(written.isEmpty());
        verify(poll).suspend(30_000L);
        verify(poll, never()).resume();
        LongPollingReplies.discard(id);
    }

    @Test
    void aReplyParkedWhileThePollSuspendedResumesIt() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        var written = new ArrayList<String>();
        var poll = poll(id, null, written);
        // The reply ends between the poll's look-up and its suspension: no poll
        // is suspended for the hand-over to resume.
        when(poll.suspend(anyLong())).thenAnswer(inv -> {
            replyEnded(id, null, "late");
            when(poll.isSuspended()).thenReturn(true);
            return poll;
        });

        handler.onRequest(poll);

        verify(poll).resume();
        var next = poll(id, null, written);
        suspendRegisters(next);
        handler.onRequest(next);
        assertTrue(written.get(0).contains("\"late\""));
    }

    @Test
    void aPollWhileTheReplyIsRunningOrParkedDoesNotReplayTheRun() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        var asked = mock(AtmosphereResource.class);
        when(asked.uuid()).thenReturn(id);
        when(asked.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(asked.getAtmosphereConfig()).thenReturn(config);
        when(asked.getBroadcaster()).thenReturn(broadcaster);
        when(asked.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        var running = StreamingSessions.start(asked);
        running.send("still running");

        var poll = poll(id, null, new ArrayList<>());
        suspendRegisters(poll);
        handler.onRequest(poll);
        verify(poll.getRequest(), never()).getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE);
        verify(poll.getRequest(), never()).getHeader(RunReattachSupport.RUN_ID_HEADER);

        running.complete();
        LongPollingReplies.discard(id);
    }

    @Test
    void aPollWhileAReplyWaitsForAnotherPollDoesNotReplayTheRun() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        replyEnded(id, "alice", "for alice's next poll");
        // Another identity's poll is not handed the reply, so it reaches the
        // reattach step with the reply parked and no session running.
        var poll = poll(id, "bob", new ArrayList<>());
        suspendRegisters(poll);

        handler.onRequest(poll);

        verify(poll.getRequest(), never()).getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE);
        LongPollingReplies.discard(id);
    }

    @Test
    void aPollWithNoReplyReplaysAsBefore() throws Exception {
        var poll = poll("lp-" + UUID.randomUUID(), null, new ArrayList<>());
        suspendRegisters(poll);

        handler.onRequest(poll);

        verify(poll.getRequest()).getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE);
    }

    @AiEndpoint(path = PATH)
    static class StubEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }
}
