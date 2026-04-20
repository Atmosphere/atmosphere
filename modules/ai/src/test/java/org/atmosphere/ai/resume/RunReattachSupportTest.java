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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Regression for the run-reattach consumer path. A client that
 * disconnects mid-stream and reconnects carrying
 * {@code X-Atmosphere-Run-Id} must receive every event the replay
 * buffer captured while it was gone — no duplicates on peer
 * subscribers, direct writes to the reconnecting resource only.
 *
 * <p>Tests exercise {@link RunReattachSupport#replayPendingRun}
 * directly so the reattach semantics are provable without standing up
 * a full {@code AiEndpointHandler} + transport stack. The three
 * realistic shapes are covered: (a) no run id → silent pass; (b)
 * known run id, buffered events → replay; (c) header fallback when
 * the durable-sessions interceptor is not in the chain.</p>
 */
class RunReattachSupportTest {

    private RunRegistry registry;
    private AgentResumeHandle handle;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry();
        // A real run on the registry with a settable execution handle —
        // replayable events exercise the live event list, not a mock.
        handle = registry.register("agent-a", "alice", "sess-1",
                new ExecutionHandle.Settable(() -> { }));
    }

    @Test
    void replayIsNoOpWhenResourceHasNoRunIdAttrOrHeader() {
        var writes = new ArrayList<String>();
        var resource = mockResourceWithRunId(null, null, writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(0, replayed);
        assertTrue(writes.isEmpty(),
                "fresh connection must not trigger any replay writes");
    }

    @Test
    void replayIsNoOpWhenRunIdUnknown() {
        var writes = new ArrayList<String>();
        var resource = mockResourceWithRunId("never-seen-runId", null, writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(0, replayed);
        assertTrue(writes.isEmpty(),
                "unknown run id must not trigger any replay writes — "
                + "the run may have expired; treat as a fresh session");
    }

    @Test
    void replayDrainsAllBufferedEventsInOrderToReconnectingResource() {
        // Simulate the pipeline capturing a few events while the client
        // was disconnected — matches what StreamingSession writes into
        // the RunEventReplayBuffer on real runs.
        handle.replayBuffer().capture("streaming-text", "Hello, ");
        handle.replayBuffer().capture("streaming-text", "how can I help?");
        handle.replayBuffer().capture("complete", "[complete]");

        var writes = new ArrayList<String>();
        var resource = mockResourceWithRunId(handle.runId(), null, writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(3, replayed, "all three buffered events must reach the new resource");
        assertEquals(1, writes.size(),
                "replay must batch events into a single write — long-polling "
                + "flushes once per resource.write and the first flush resumes the "
                + "response, so individual per-event writes drop everything after "
                + "the first");
        // The batched payload is newline-delimited AiStreamMessage JSON,
        // matching the live DefaultStreamingSession wire shape so the
        // client parser can handle replay and live frames identically.
        var lines = writes.get(0).split("\\n");
        assertEquals(3, lines.length,
                "one JSON frame per captured event: " + writes.get(0));
        assertTrue(lines[0].contains("\"type\":\"streaming-text\"")
                        && lines[0].contains("\"data\":\"Hello, \""),
                "first frame must be streaming-text with original payload: " + lines[0]);
        assertTrue(lines[1].contains("\"data\":\"how can I help?\""),
                "second frame preserves order: " + lines[1]);
        assertTrue(lines[2].contains("\"type\":\"complete\"")
                        && lines[2].contains("\"data\":\"[complete]\""),
                "terminal frame must be complete envelope carrying summary: " + lines[2]);
        assertTrue(lines[0].contains("\"sessionId\":\"" + handle.runId() + "\""),
                "sessionId on replay frames must be the run id so the client "
                + "can distinguish replay from live frames: " + lines[0]);
    }

    @Test
    void replayFallsBackToHeaderWhenAttributeIsAbsent() {
        handle.replayBuffer().capture("streaming-text", "raw-client frame");
        var writes = new ArrayList<String>();
        // Null attribute, run id only in the HTTP header — raw WS clients
        // bypass DurableSessionInterceptor but still carry the id.
        var resource = mockResourceWithRunId(null, handle.runId(), writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(1, replayed,
                "X-Atmosphere-Run-Id header must be accepted when the interceptor "
                + "attribute is absent — raw-WebSocket clients rely on it");
    }

    /**
     * Ownership check: a run originated by {@code alice} must not
     * replay to {@code mallory} even if mallory happens to carry the
     * correct run id. The run id is a bearer token — without principal
     * binding, any caller who guesses or obtains one replays another
     * user's stream. Regression for the P0 cross-tenant leak.
     */
    @Test
    void replayRefusedWhenCallerDoesNotOwnRun() {
        handle.replayBuffer().capture("streaming-text", "alice's secrets");
        var writes = new ArrayList<String>();
        var resource = mockResourceWithRunIdAndCaller(
                handle.runId(), null, "mallory", writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(0, replayed,
                "cross-user replay must be refused — the run id is a bearer "
                + "token and the registry-recorded userId is the ownership check");
        assertTrue(writes.isEmpty() || writes.get(0).isEmpty(),
                "no bytes may be written on a refused replay");
    }

    /**
     * Anonymous-runner carve-out: a run registered without auth
     * (userId == "anonymous") relies on runId entropy for protection;
     * the reconnecting caller need not be authenticated. This is the
     * open-mode deployment path.
     */
    @Test
    void replayAllowedForAnonymousRunsRegardlessOfCallerIdentity() {
        // Re-register with anonymous userId — what AiEndpointHandler
        // does when no principal is resolved on the dispatch thread.
        var anon = registry.register("agent-a", "anonymous", "sess-anon",
                new ExecutionHandle.Settable(() -> { }));
        anon.replayBuffer().capture("streaming-text", "open-mode event");

        var writes = new ArrayList<String>();
        // Caller is anonymous too (no principal attribute resolved).
        var resource = mockResourceWithRunIdAndCaller(
                anon.runId(), null, null, writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(1, replayed,
                "anonymous runs must replay without ownership enforcement — "
                + "open-mode deployments rely on runId entropy");
    }

    /**
     * Replay MUST apply the broadcaster's BroadcastFilter chain to
     * each frame so response-path protections (PII redaction, content
     * safety, etc.) see replayed frames identically to live frames.
     * Regression for the P0 cross-session leak where a direct
     * response.getWriter() write bypassed PiiRedactionFilter and
     * leaked content the live path would have redacted.
     */
    @Test
    void replayRoutesFramesThroughBroadcasterFilterChain() {
        handle.replayBuffer().capture("streaming-text", "contact me at alice@example.com");
        var writes = new ArrayList<String>();
        var resource = mockResourceWithRunIdAndCaller(
                handle.runId(), null, "alice", writes);
        // Install the PiiRedactionFilter on the resource's broadcaster
        // so applyFilters picks it up.
        var broadcaster = Mockito.mock(org.atmosphere.cpr.Broadcaster.class);
        var config = Mockito.mock(org.atmosphere.cpr.BroadcasterConfig.class);
        Mockito.when(resource.getBroadcaster()).thenReturn(broadcaster);
        Mockito.when(broadcaster.getBroadcasterConfig()).thenReturn(config);
        Mockito.when(broadcaster.getID()).thenReturn("/atmosphere/agent/test");
        var pii = new org.atmosphere.ai.filter.PiiRedactionFilter("[redacted-email]");
        java.util.List<org.atmosphere.cpr.BroadcastFilter> filterList = new ArrayList<>();
        filterList.add(pii);
        Mockito.when(config.filters()).thenReturn(filterList);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(1, replayed, "one frame replayed (filter transformed, didn't abort)");
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).contains("[redacted-email]"),
                "PII redaction must apply to replay frames identically to live "
                + "frames — a direct response.getWriter() write that bypasses the "
                + "filter chain would leak the email back to the client: "
                + writes.get(0));
        assertTrue(!writes.get(0).contains("alice@example.com"),
                "original PII must not leak on the replay path: " + writes.get(0));
    }

    @Test
    void replayReportsZeroWhenWriteFails() throws java.io.IOException {
        handle.replayBuffer().capture("streaming-text", "first");
        handle.replayBuffer().capture("streaming-text", "second");
        handle.replayBuffer().capture("streaming-text", "third");

        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        var response = Mockito.mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE))
                .thenReturn(handle.runId());
        when(request.getAttribute("ai.userId")).thenReturn("alice");
        when(resource.uuid()).thenReturn("res-1");
        when(resource.getResponse()).thenReturn(response);
        // The writer acquisition throws IOException — callers see 0 events
        // delivered so they can re-register and retry on a fresh resource.
        when(response.getWriter()).thenThrow(new java.io.IOException("socket closed"));

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(0, replayed,
                "when the batched write fails, zero events reached the client — "
                + "reporting anything else would mislead retry logic into "
                + "assuming partial delivery");
    }

    private static AtmosphereResource mockResourceWithRunId(
            String attributeValue, String headerValue, List<String> writes) {
        // Default: caller identity matches the run's userId ("alice" —
        // see setUp) so the ownership check admits. Per-test variants
        // use mockResourceWithRunIdAndCaller to exercise mismatch paths.
        return mockResourceWithRunIdAndCaller(attributeValue, headerValue, "alice", writes);
    }

    private static AtmosphereResource mockResourceWithRunIdAndCaller(
            String attributeValue, String headerValue, String callerUserId,
            List<String> writes) {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        var response = Mockito.mock(AtmosphereResponse.class);
        var stringWriter = new java.io.StringWriter();
        var printWriter = new java.io.PrintWriter(stringWriter) {
            @Override public void flush() {
                super.flush();
                // Snapshot what the writer has accumulated so the test
                // can assert the exact payload produced by replay.
                writes.clear();
                writes.add(stringWriter.toString());
            }
        };
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE))
                .thenReturn(attributeValue);
        when(request.getHeader(RunReattachSupport.RUN_ID_HEADER))
                .thenReturn(headerValue);
        // Caller identity via the ai.userId attribute — same chain
        // AiEndpointHandler populates on dispatch. Allows each test to
        // steer the ownership decision.
        when(request.getAttribute("ai.userId")).thenReturn(callerUserId);
        when(resource.uuid()).thenReturn("res-" + System.identityHashCode(resource));
        try {
            when(resource.getResponse()).thenReturn(response);
            when(response.getWriter()).thenReturn(printWriter);
        } catch (java.io.IOException e) {
            throw new AssertionError("mock cannot throw", e);
        }
        return resource;
    }
}
