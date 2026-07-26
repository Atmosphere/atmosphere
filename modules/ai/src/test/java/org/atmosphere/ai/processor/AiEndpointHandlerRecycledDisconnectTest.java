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
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.DefaultStreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the recycled-resource disconnect path.
 *
 * <p>Tomcat can fire the async error/cancel listener after the async context
 * has been torn down, at which point {@code AtmosphereResourceEvent.getResource()}
 * is null. The handler used to early-return on that path, skipping memory and
 * session cleanup entirely and leaking an entry in each process-global map for
 * every recycled disconnect (Correctness Invariant #3). The event still carries
 * the UUID it was constructed with, so everything keyed by UUID stays
 * reclaimable.</p>
 */
class AiEndpointHandlerRecycledDisconnectTest {

    private AiEndpointHandler handler;
    private RecordingInterceptor interceptor;

    /** Minimal {@code @Prompt} target — the disconnect path never invokes it. */
    static class StubEndpoint {
        @Prompt
        public void onPrompt(String message) {
            // Not exercised by the disconnect path.
        }
    }

    /** Captures the conversation id the handler reports on disconnect. */
    static final class RecordingInterceptor implements AiInterceptor {
        final AtomicReference<String> conversationId = new AtomicReference<>();
        final AtomicReference<String> userId = new AtomicReference<>();
        int calls;

        @Override
        public void onDisconnect(String userId, String conversationId, List<ChatMessage> history) {
            this.calls++;
            this.userId.set(userId);
            this.conversationId.set(conversationId);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Method promptMethod = StubEndpoint.class.getMethod("onPrompt", String.class);
        interceptor = new RecordingInterceptor();
        handler = new AiEndpointHandler(
                new StubEndpoint(),
                promptMethod,
                30_000L,
                "",
                mock(AgentRuntime.class),
                List.<AiInterceptor>of(interceptor));
    }

    /**
     * Build the exact event shape Tomcat delivers after recycling: the
     * resource reference is stripped by {@code destroy()} but the captured
     * uuid survives.
     */
    private static AtmosphereResourceEventImpl recycledEventFor(String uuid) {
        var resourceImpl = mock(AtmosphereResourceImpl.class);
        when(resourceImpl.uuid()).thenReturn(uuid);
        var event = new AtmosphereResourceEventImpl(resourceImpl);
        event.destroy();
        return event;
    }

    @Test
    void recycledEventStillCarriesItsResourceUuid() {
        var event = recycledEventFor("uuid-recycled-basic");

        assertNull(event.getResource(), "destroy() must strip the resource reference");
        assertEquals("uuid-recycled-basic", event.uuid(),
                "the uuid captured at construction must survive recycling — it is the "
                        + "only handle the cleanup path has left");
    }

    @Test
    void recycledDisconnectReclaimsStreamingSession() throws Exception {
        var uuid = "uuid-recycled-streaming";
        var resource = mock(org.atmosphere.cpr.AtmosphereResource.class);
        var broadcaster = mock(org.atmosphere.cpr.Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn(uuid);

        var session = StreamingSessions.start("recycled-session", resource);
        assertTrue(DefaultStreamingSession.resourceForSession("recycled-session").isPresent(),
                "precondition: the session is registered");

        handler.onStateChange(recycledEventFor(uuid));

        assertTrue(DefaultStreamingSession.resourceForSession("recycled-session").isEmpty(),
                "a recycled disconnect must still reclaim the streaming-session entry");
        assertTrue(session.isClosed(), "the reclaimed session must be marked closed");
    }

    @Test
    void recycledDisconnectReclaimsActiveAiSession() throws Exception {
        var uuid = "uuid-recycled-active";
        var resource = mock(org.atmosphere.cpr.AtmosphereResource.class);
        when(resource.uuid()).thenReturn(uuid);

        var aiSession = new AiStreamingSession(
                new CollectingSession(), mock(AgentRuntime.class), "", null,
                List.of(), resource);
        AiStreamingSession.registerActive(aiSession);
        assertTrue(AiStreamingSession.resourceHasActiveSessions(uuid),
                "precondition: the AI session is registered");

        handler.onStateChange(recycledEventFor(uuid));

        assertFalse(AiStreamingSession.resourceHasActiveSessions(uuid),
                "a recycled disconnect must still reclaim the active AI session");
    }

    @Test
    void recycledDisconnectStillNotifiesInterceptorsWithTheConversationId() throws Exception {
        handler.onStateChange(recycledEventFor("uuid-recycled-interceptor"));

        assertEquals(1, interceptor.calls,
                "interceptors must still see the disconnect signal");
        assertEquals("uuid-recycled-interceptor", interceptor.conversationId.get(),
                "the conversation id is the resource uuid, which survives recycling");
        assertNull(interceptor.userId.get(),
                "request attributes are gone with the recycled request — userId is null here");
    }

    @Test
    void normalDisconnectStillReclaimsAndReportsUserId() throws Exception {
        var uuid = "uuid-normal-disconnect";
        var resourceImpl = mock(AtmosphereResourceImpl.class);
        var request = mock(org.atmosphere.cpr.AtmosphereRequest.class);
        var broadcaster = mock(org.atmosphere.cpr.Broadcaster.class);
        when(resourceImpl.uuid()).thenReturn(uuid);
        when(resourceImpl.getRequest()).thenReturn(request);
        when(resourceImpl.getBroadcaster()).thenReturn(broadcaster);
        when(request.getAttribute("ai.userId")).thenReturn("alice");

        var session = StreamingSessions.start("normal-session", resourceImpl);
        // isClosedByClient=true drives the same handleDisconnect entry with a
        // live resource — the parity half of the recycled case (Invariant #7).
        var event = new AtmosphereResourceEventImpl(resourceImpl, false, false, true, null);

        handler.onStateChange(event);

        assertNotNull(event.getResource(), "precondition: this event is NOT recycled");
        assertTrue(DefaultStreamingSession.resourceForSession("normal-session").isEmpty(),
                "the live-resource path must reclaim the same state");
        assertTrue(session.isClosed());
        assertEquals(uuid, interceptor.conversationId.get());
        assertEquals("alice", interceptor.userId.get(),
                "with a live request the userId attribute must still be reported");
    }
}
