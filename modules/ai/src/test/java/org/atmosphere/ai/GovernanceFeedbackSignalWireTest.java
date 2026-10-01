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
package org.atmosphere.ai;

import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernanceFeedbackInterceptor;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the client-visible governance-feedback signal on the real wire: a
 * {@link GovernanceFeedbackInterceptor} wired into an {@link AiStreamingSession}
 * over a REAL {@link DefaultStreamingSession} must put the
 * {@code ai.governance.feedback.*} frames on the wire before the terminal
 * {@code complete} frame exactly when it injected guidance into the prompt the
 * runtime received — and on the {@link AiPipeline} path, which runs no
 * interceptor, neither the injection nor the signal may appear.
 */
class GovernanceFeedbackSignalWireTest {

    private static final String PREFERRED = "open a CHG ticket and run release-bot";
    private static final String INJECTED = "\"" + GovernanceFeedbackInterceptor.INJECTED_METADATA_KEY + "\"";
    private static final String LINES = "\"" + GovernanceFeedbackInterceptor.LINES_METADATA_KEY + "\"";

    @BeforeEach
    void install() {
        GovernanceDecisionLog.install(100);
    }

    @AfterEach
    void cleanup() {
        GovernanceDecisionLog.reset();
    }

    private record Wire(AtmosphereResource resource, List<String> frames, StreamingSession delegate) {
    }

    /** A per-client turn, stamped as the {@code @AiEndpoint} handler stamps it. */
    private static Wire wire(String uuid) {
        return wire(uuid, false);
    }

    /**
     * A turn whose reply is per-client ({@code room = false}, {@link StreamingSessions#start})
     * or room-wide ({@code room = true}, {@link StreamingSessions#startRoomBroadcast}). Frames
     * are captured from both broadcaster overloads: the targeted one (per-client) and the
     * untargeted one (every subscriber in the room).
     */
    private static Wire wire(String uuid, boolean room) {
        var frames = new CopyOnWriteArrayList<String>();
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        var request = mock(AtmosphereRequest.class);
        when(request.getAttribute(StreamingSessions.ROOM_BROADCAST_ATTRIBUTE)).thenReturn(room);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(), anySet())).thenAnswer(inv -> {
            frames.add(String.valueOf(((RawMessage) inv.getArgument(0)).message()));
            return null;
        });
        when(broadcaster.broadcast(any())).thenAnswer(inv -> {
            frames.add(String.valueOf(((RawMessage) inv.getArgument(0)).message()));
            return null;
        });
        return new Wire(resource, frames, room
                ? StreamingSessions.startRoomBroadcast(resource)
                : StreamingSessions.start("sess-" + uuid, resource));
    }

    /** A PREFER for {@code conversationId}, as the policy plane records it. */
    private static void recordPrefer(String conversationId) {
        GovernanceDecisionLog.installed().record(GovernanceDecisionLog.entryWithSnapshot(
                new AdmitPolicy(), Map.of("conversation_id", conversationId,
                        GovernanceDecisionLog.PREFERRED_KEY, PREFERRED),
                "prefer", "change management", 0.1));
    }

    /** Captures the system prompt it was given and completes mid-execute like real bridges. */
    private static AgentRuntime capturingRuntime(AtomicReference<String> systemPrompt) {
        return new AgentRuntime() {
            @Override public String name() { return "capturing"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                systemPrompt.set(context.systemPrompt());
                session.send("answer");
                session.complete();
            }
        };
    }

    private static int indexOf(List<String> frames, String token) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).contains(token)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void injectedGuidanceIsSignalledOnTheWireBeforeComplete() {
        var w = wire("conv-signal");
        recordPrefer("conv-signal");
        var prompt = new AtomicReference<String>();
        var session = new AiStreamingSession(w.delegate(), capturingRuntime(prompt), "base", null,
                List.of(new GovernanceFeedbackInterceptor()), w.resource());

        session.stream("how do I deploy to production?");

        assertTrue(prompt.get().contains(PREFERRED),
                "the runtime received the injected guidance: " + prompt.get());
        var injectedIdx = indexOf(w.frames(), INJECTED);
        var linesIdx = indexOf(w.frames(), LINES);
        var completeIdx = indexOf(w.frames(), "\"type\":\"complete\"");
        assertTrue(injectedIdx >= 0 && w.frames().get(injectedIdx).contains("\"value\":1"),
                "count frame on the wire: " + w.frames());
        assertTrue(linesIdx >= 0 && w.frames().get(linesIdx).contains("release-bot"),
                "lines frame names the injected guidance: " + w.frames());
        assertTrue(completeIdx > linesIdx && completeIdx > injectedIdx,
                "signal precedes the terminal complete frame: " + w.frames());
    }

    @Test
    void anotherConversationsGuidanceIsNeitherInjectedNorSignalled() {
        var w = wire("conv-mine");
        recordPrefer("conv-someone-else");
        var prompt = new AtomicReference<String>();
        var session = new AiStreamingSession(w.delegate(), capturingRuntime(prompt), "base", null,
                List.of(new GovernanceFeedbackInterceptor()), w.resource());

        session.stream("how do I deploy to production?");

        assertFalse(prompt.get().contains(PREFERRED), "no cross-conversation injection");
        assertTrue(indexOf(w.frames(), "ai.governance.feedback") < 0,
                "no signal without injection: " + w.frames());
        assertTrue(indexOf(w.frames(), "\"type\":\"complete\"") >= 0, "turn still completes");
    }

    @Test
    void roomBroadcastReplyCarriesNoGovernanceFrame() {
        // @AiEndpoint(broadcastReply = true): the reply fans out to every subscriber in the
        // room, so the prompter's own governance lines must not ride it.
        var w = wire("conv-room", true);
        recordPrefer("conv-room");
        var prompt = new AtomicReference<String>();
        var session = new AiStreamingSession(w.delegate(), capturingRuntime(prompt), "base", null,
                List.of(new GovernanceFeedbackInterceptor()), w.resource());

        session.stream("how do I deploy to production?");

        assertTrue(prompt.get().contains(PREFERRED),
                "the prompter's turn still carries the guidance: " + prompt.get());
        assertTrue(indexOf(w.frames(), "answer") >= 0
                        && indexOf(w.frames(), "\"type\":\"complete\"") >= 0,
                "the reply and its complete frame went to the whole room: " + w.frames());
        assertTrue(indexOf(w.frames(), "ai.governance.feedback") < 0,
                "no governance frame reaches the room: " + w.frames());
    }

    @Test
    void pipelinePathRunsNoInterceptorSoNeitherInjectsNorSignals() {
        recordPrefer("pipeline-client");
        var prompt = new AtomicReference<String>();
        var keys = new CopyOnWriteArrayList<String>();
        var session = new CollectingSession("pipeline");
        var recording = new StreamingSession() {
            @Override public String sessionId() { return session.sessionId(); }
            @Override public void send(String text) { session.send(text); }
            @Override public void sendMetadata(String key, Object value) { keys.add(key); }
            @Override public void progress(String message) { }
            @Override public void complete() { session.complete(); }
            @Override public void complete(String summary) { session.complete(summary); }
            @Override public void error(Throwable t) { session.error(t); }
            @Override public boolean isClosed() { return session.isClosed(); }
        };
        var pipeline = new AiPipeline(capturingRuntime(prompt), "base", null, null, null,
                List.of(), List.of(), List.of(), null, null);

        pipeline.execute("pipeline-client", "how do I deploy to production?", recording);

        assertFalse(prompt.get().contains(PREFERRED),
                "AiPipeline runs no AiInterceptor, so no guidance is injected: " + prompt.get());
        assertTrue(keys.stream().noneMatch(k -> k.startsWith("ai.governance.feedback")),
                "and no injection is advertised: " + keys);
    }

    private static final class AdmitPolicy implements GovernancePolicy {
        @Override public String name() { return "production-release-advisor"; }
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext context) {
            return PolicyDecision.admit();
        }
    }
}
