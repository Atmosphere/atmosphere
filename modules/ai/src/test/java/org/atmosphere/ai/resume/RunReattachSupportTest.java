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
     * Fail-closed carve-in: a run whose owner could not be resolved at
     * registration (the container threw from getUserPrincipal, so
     * ownership is indeterminate — NOT the same as "no auth
     * configured") must refuse every reattach. Regression for the
     * authenticated-deployment leak where such runs registered as
     * "anonymous" and the anonymous carve-out waved any runId holder
     * through.
     */
    @Test
    void replayRefusedForUnresolvedOwnerRunRegardlessOfCaller() {
        var unresolved = registry.register("agent-a",
                RunReattachSupport.UNRESOLVED, "sess-x",
                new ExecutionHandle.Settable(() -> { }));
        unresolved.replayBuffer().capture("streaming-text", "indeterminate-owner event");

        var writes = new ArrayList<String>();
        // Anonymous caller — exactly the caller the old bug admitted.
        var anonCaller = mockResourceWithRunIdAndCaller(
                unresolved.runId(), null, null, writes);
        assertEquals(0, RunReattachSupport.replayPendingRun(anonCaller, registry),
                "an unresolved-owner run must fail closed for anonymous callers");

        // Defense in depth: even a caller presenting the sentinel string
        // itself as its identity must not match.
        var sentinelCaller = mockResourceWithRunIdAndCaller(
                unresolved.runId(), null, RunReattachSupport.UNRESOLVED, writes);
        assertEquals(0, RunReattachSupport.replayPendingRun(sentinelCaller, registry),
                "the sentinel value must never satisfy the ownership check");
        assertTrue(writes.isEmpty() || writes.get(0).isEmpty(),
                "no bytes may be written on a refused replay");
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

    /**
     * A replay belongs to the reattaching resource alone. A
     * ClusterBroadcastFilter on its broadcaster would publish each private
     * frame, and every other node would deliver it to all its subscribers;
     * the local filters still run.
     */
    @Test
    void replaySkipsClusterBroadcastFilters() {
        handle.replayBuffer().capture("streaming-text", "alice private answer");
        var writes = new ArrayList<String>();
        var resource = mockResourceWithRunIdAndCaller(
                handle.runId(), null, "alice", writes);
        var broadcaster = Mockito.mock(org.atmosphere.cpr.Broadcaster.class);
        var config = Mockito.mock(org.atmosphere.cpr.BroadcasterConfig.class);
        Mockito.when(resource.getBroadcaster()).thenReturn(broadcaster);
        Mockito.when(broadcaster.getBroadcasterConfig()).thenReturn(config);
        Mockito.when(broadcaster.getID()).thenReturn("/atmosphere/agent/test");
        var published = new ArrayList<Object>();
        var cluster = Mockito.mock(org.atmosphere.cpr.ClusterBroadcastFilter.class);
        Mockito.when(cluster.filter(Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> {
                    published.add(inv.getArgument(2));
                    return new org.atmosphere.cpr.BroadcastFilter.BroadcastAction(inv.getArgument(2));
                });
        var local = new ArrayList<Object>();
        org.atmosphere.cpr.BroadcastFilter localFilter = (id, original, message) -> {
            local.add(message);
            return new org.atmosphere.cpr.BroadcastFilter.BroadcastAction(message);
        };
        java.util.List<org.atmosphere.cpr.BroadcastFilter> filterList = new ArrayList<>();
        filterList.add(cluster);
        filterList.add(localFilter);
        Mockito.when(config.filters()).thenReturn(filterList);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(1, replayed);
        assertTrue(writes.get(0).contains("alice private answer"), writes.get(0));
        assertEquals(1, local.size(), "local filters must still run on replay frames");
        assertTrue(published.isEmpty(), "replay published to the cluster: " + published);
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

    /**
     * A long-polling poll answers with its response only once it is resumed:
     * the replay, written straight to the response, must end the poll after
     * the flush, or the client sees it only at the poll's suspend timeout.
     */
    @Test
    void longPollingReplayResumesThePollAfterTheFlush() {
        handle.replayBuffer().capture("streaming-text", "missed while away");
        handle.replayBuffer().capture("complete", "[complete]");
        var steps = new ArrayList<String>();
        var resource = suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, steps);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(2, replayed);
        assertEquals(1, java.util.Collections.frequency(steps, "resume"), "the poll must be resumed once: " + steps);
        assertEquals("resume", steps.get(steps.size() - 1), "the poll must be resumed after the flush: " + steps);
        assertTrue(steps.indexOf("flush") >= 0, "the replay must be flushed: " + steps);
    }

    /**
     * atmosphere.js re-sends the run id on every poll until it reads a terminal
     * frame, and a batched replay is not read as one. A second poll of the same
     * tracking id with nothing new must not be answered with the replay again:
     * resumed at once, it made a request loop for the registry TTL.
     */
    @Test
    void longPollingRunIdPollWithNothingNewIsNotAnsweredWithTheReplayAgain() {
        handle.replayBuffer().capture("streaming-text", "missed while away");
        handle.replayBuffer().capture("complete", "[complete]");
        var first = new ArrayList<String>();
        assertEquals(2, RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, first), registry));

        var steps = new ArrayList<String>();
        var replayed = RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, steps), registry);

        assertEquals(0, replayed, "nothing new for this tracking id");
        assertTrue(steps.isEmpty(), "the poll must stay suspended, unwritten: " + steps);
        assertEquals(0, handle.replayBuffer().subscriberCount(), "a finished run holds no listener");
    }

    @Test
    void longPollingRunIdPollOfARunStillGoingIsAnsweredWithItsNextEventOnly() {
        handle.replayBuffer().capture("streaming-text", "first");
        RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>()), registry);

        var steps = new ArrayList<String>();
        var sink = new java.io.StringWriter();
        assertEquals(0, RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, steps, sink), registry));
        assertTrue(steps.isEmpty(), "nothing new yet: the poll waits: " + steps);

        handle.replayBuffer().capture("streaming-text", "second");

        assertEquals("resume", steps.get(steps.size() - 1), "the next event answers the poll: " + steps);
        assertTrue(sink.toString().contains("\"data\":\"second\""), sink.toString());
        assertTrue(!sink.toString().contains("\"data\":\"first\""), "no event twice: " + sink);
        assertTrue(sink.toString().contains("\"seq\":2"), "frames number by the run's own sequence: " + sink);
        assertEquals(0, handle.replayBuffer().subscriberCount(), "answered: the poll stops listening");
    }

    @Test
    void aWaitingPollThatEndsStopsListeningToTheRun() {
        handle.replayBuffer().capture("streaming-text", "first");
        RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>()), registry);
        var steps = new ArrayList<String>();
        var poll = suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, steps);
        RunReattachSupport.replayPendingRun(poll, registry);
        assertEquals(1, handle.replayBuffer().subscriberCount());
        var listener = org.mockito.ArgumentCaptor.forClass(org.atmosphere.cpr.AtmosphereResourceEventListener.class);
        Mockito.verify(poll).addEventListener(listener.capture());

        listener.getValue().onResume(null);
        handle.replayBuffer().capture("streaming-text", "second");

        assertEquals(0, handle.replayBuffer().subscriberCount(), "the poll's end releases its listener");
        assertTrue(steps.isEmpty(), "nothing is written to a poll that ended: " + steps);
    }

    /**
     * Long-polling polls waiting for a run's next event must not take the slots
     * of streaming reattaches: with {@link RunEventReplayBuffer#MAX_SUBSCRIBERS}
     * polls waiting, an SSE reattach to the same run got its backlog and never
     * the rest of the run or its terminal frame.
     */
    @Test
    void waitingLongPollingPollsLeaveRoomForAStreamingReattach() {
        handle.replayBuffer().capture("streaming-text", "first");
        for (int i = 0; i < RunEventReplayBuffer.MAX_SUBSCRIBERS; i++) {
            var id = "lp-" + i;
            RunReattachSupport.replayPendingRun(
                    suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(),
                            new java.io.StringWriter(), id), registry);
            var waiting = new ArrayList<String>();
            assertEquals(0, RunReattachSupport.replayPendingRun(
                    suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, waiting,
                            new java.io.StringWriter(), id), registry));
            assertTrue(waiting.isEmpty(), "nothing new yet: poll " + id + " waits: " + waiting);
        }
        var sse = new java.io.StringWriter();

        assertEquals(1, RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.SSE, new ArrayList<>(), sse, "sse-client"), registry));
        handle.replayBuffer().capture("streaming-text", "second");
        handle.replayBuffer().capture("complete", "[complete]");

        assertTrue(sse.toString().contains("\"data\":\"second\""),
                "the SSE reattach must receive the rest of the run: " + sse);
        assertTrue(sse.toString().contains("\"type\":\"complete\""),
                "the SSE reattach must receive the run's terminal frame: " + sse);
    }

    /**
     * Every poll waiting for a run's next event is answered with it, past the
     * streaming reattach cap: the ninth waiting poll was not subscribed and stayed
     * suspended, unanswered, until its suspend timeout.
     */
    @Test
    void everyWaitingLongPollingPollIsAnsweredWithTheRunsNextEvent() {
        handle.replayBuffer().capture("streaming-text", "first");
        var waiters = RunEventReplayBuffer.MAX_SUBSCRIBERS + 2;
        var steps = new ArrayList<List<String>>();
        var sinks = new ArrayList<java.io.StringWriter>();
        for (int i = 0; i < waiters; i++) {
            var id = "lp-" + i;
            RunReattachSupport.replayPendingRun(
                    suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(),
                            new java.io.StringWriter(), id), registry);
            var waiting = new ArrayList<String>();
            var sink = new java.io.StringWriter();
            RunReattachSupport.replayPendingRun(
                    suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, waiting, sink, id), registry);
            steps.add(waiting);
            sinks.add(sink);
        }

        handle.replayBuffer().capture("streaming-text", "second");

        for (int i = 0; i < waiters; i++) {
            assertTrue(steps.get(i).contains("resume"), "waiting poll " + i + " must be answered: " + steps.get(i));
            assertTrue(sinks.get(i).toString().contains("\"data\":\"second\""),
                    "waiting poll " + i + " must get the run's next event: " + sinks.get(i));
        }
        assertEquals(0, handle.replayBuffer().subscriberCount(), "answered: no poll keeps listening");
    }

    /**
     * A newer poll of a tracking id takes over the wait of its older one rather
     * than a second place: the polls waiting on a run are bounded by its clients.
     */
    @Test
    void aNewerPollOfATrackingIdTakesOverTheWaitOfItsOlderOne() {
        handle.replayBuffer().capture("streaming-text", "first");
        RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(),
                        new java.io.StringWriter(), "lp-a"), registry);
        var older = new ArrayList<String>();
        RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, older, new java.io.StringWriter(), "lp-a"),
                registry);
        var newer = new ArrayList<String>();
        var sink = new java.io.StringWriter();
        RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, newer, sink, "lp-a"), registry);

        assertEquals(1, handle.replayBuffer().subscriberCount(), "one waiting poll per tracking id");
        handle.replayBuffer().capture("streaming-text", "second");

        assertTrue(newer.contains("resume") && sink.toString().contains("\"data\":\"second\""),
                "the newer poll gets the run's next event: " + newer + " " + sink);
        assertTrue(older.isEmpty(), "the older poll is not written: " + older);
    }

    /**
     * Past {@link RunEventReplayBuffer#MAX_WAITING_POLLS} tracking ids waiting on
     * one run, a poll waits for a reply or its suspend timeout instead (it is not
     * resumed empty, which would have the client poll again at once), and its
     * client's next poll gets what it missed.
     */
    @Test
    void aPollPastTheWaitingBoundCatchesUpOnItsNextPoll() {
        handle.replayBuffer().capture("streaming-text", "first");
        for (int i = 0; i < RunEventReplayBuffer.MAX_WAITING_POLLS; i++) {
            var id = "lp-" + i;
            RunReattachSupport.replayPendingRun(
                    suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(),
                            new java.io.StringWriter(), id), registry);
            RunReattachSupport.replayPendingRun(
                    suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(),
                            new java.io.StringWriter(), id), registry);
        }
        RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(),
                        new java.io.StringWriter(), "lp-late"), registry);
        var past = new ArrayList<String>();
        assertEquals(0, RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, past, new java.io.StringWriter(), "lp-late"),
                registry));
        assertEquals(RunEventReplayBuffer.MAX_WAITING_POLLS, handle.replayBuffer().subscriberCount());

        handle.replayBuffer().capture("streaming-text", "second");

        assertTrue(past.isEmpty(), "past the bound the poll is left suspended, unwritten: " + past);
        // Past MAX_REPLAY_POSITIONS consumers its position may have been
        // forgotten, and it is then handed the retained events again.
        var sink = new java.io.StringWriter();
        assertTrue(RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(), sink, "lp-late"),
                registry) >= 1);
        assertTrue(sink.toString().contains("\"data\":\"second\""), "the next poll catches up: " + sink);
    }

    @Test
    void aReplayWhoseWriteFailedIsHandedToTheNextPoll() throws Exception {
        handle.replayBuffer().capture("streaming-text", "first");
        var failing = suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>());
        when(failing.getResponse().getWriter()).thenThrow(new java.io.IOException("socket closed"));
        assertEquals(0, RunReattachSupport.replayPendingRun(failing, registry));

        var sink = new java.io.StringWriter();
        assertEquals(1, RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>(), sink), registry));
        assertTrue(sink.toString().contains("\"data\":\"first\""), sink.toString());
    }

    @Test
    void longPollingPollWithNothingReplayedStaysSuspended() {
        var steps = new ArrayList<String>();
        var resource = suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, steps);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(0, replayed);
        assertTrue(steps.isEmpty(), "an empty replay must leave the poll suspended: " + steps);
    }

    @Test
    void longPollingPollWhoseReplayWasAllFilteredStaysSuspended() {
        handle.replayBuffer().capture("streaming-text", "dropped by a filter");
        var steps = new ArrayList<String>();
        var resource = suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, steps);
        var broadcaster = Mockito.mock(org.atmosphere.cpr.Broadcaster.class);
        var config = Mockito.mock(org.atmosphere.cpr.BroadcasterConfig.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.getBroadcasterConfig()).thenReturn(config);
        when(broadcaster.getID()).thenReturn("/atmosphere/ai");
        org.atmosphere.cpr.BroadcastFilter abort = (id, original, message) ->
                new org.atmosphere.cpr.BroadcastFilter.BroadcastAction(
                        org.atmosphere.cpr.BroadcastFilter.BroadcastAction.ACTION.ABORT, message);
        when(config.filters()).thenReturn(List.of(abort));

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(0, replayed);
        assertTrue(steps.isEmpty(), "nothing written, so the poll must stay suspended: " + steps);
    }

    @Test
    void streamingTransportsAreNotResumedByTheReplay() {
        handle.replayBuffer().capture("streaming-text", "missed while away");
        handle.replayBuffer().capture("complete", "[complete]");
        for (var transport : List.of(AtmosphereResource.TRANSPORT.WEBSOCKET,
                AtmosphereResource.TRANSPORT.SSE, AtmosphereResource.TRANSPORT.STREAMING)) {
            var steps = new ArrayList<String>();
            var resource = suspendedPoll(transport, steps);

            RunReattachSupport.replayPendingRun(resource, registry);

            assertTrue(!steps.contains("resume"), transport + " must stay open after the replay: " + steps);
        }
    }

    /**
     * An older atmosphere.js client keeps sending the run id after the terminal
     * frame of its own long-polling reply: its next poll must not be answered
     * with the frames that reply already brought it.
     */
    @Test
    void aLongPollingClientsRunIdPollAfterItsOwnReplyIsNotAnsweredWithTheRunAgain() {
        var trackingId = "poll-own-" + java.util.UUID.randomUUID();
        var origin = Mockito.mock(AtmosphereResource.class);
        var broadcaster = Mockito.mock(org.atmosphere.cpr.Broadcaster.class);
        var broadcasterConfig = Mockito.mock(org.atmosphere.cpr.BroadcasterConfig.class);
        when(broadcasterConfig.filters()).thenReturn(List.of());
        when(broadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        when(broadcaster.getID()).thenReturn("/ai");
        when(origin.getBroadcaster()).thenReturn(broadcaster);
        when(origin.uuid()).thenReturn(trackingId);
        when(origin.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        when(origin.getRequest()).thenReturn(Mockito.mock(AtmosphereRequest.class));
        var session = new RunEventCapturingSession(org.atmosphere.ai.StreamingSessions.start(origin),
                handle.replayBuffer());
        session.send("the whole answer");
        session.complete();
        var poll = Mockito.mock(AtmosphereResource.class);
        when(poll.uuid()).thenReturn(trackingId);
        var received = new ArrayList<String>();
        assertTrue(org.atmosphere.ai.LongPollingReplies.deliver(poll, owner -> true,
                (p, frames) -> received.addAll(frames)));
        assertEquals(2, received.size(), "the reply reached the client: " + received);

        var steps = new ArrayList<String>();
        var sink = new java.io.StringWriter();
        var replayed = RunReattachSupport.replayPendingRun(
                suspendedPoll(AtmosphereResource.TRANSPORT.LONG_POLLING, steps, sink, trackingId), registry);

        assertEquals(0, replayed, "the client already received the run as its reply: " + sink);
        assertTrue(steps.isEmpty(), "the poll must stay suspended, unwritten: " + steps);
    }

    /** A suspended connection of {@code transport} reattaching to the run; flushes and resumes land in {@code steps}. */
    private AtmosphereResource suspendedPoll(AtmosphereResource.TRANSPORT transport, List<String> steps) {
        return suspendedPoll(transport, steps, new java.io.StringWriter());
    }

    /** As above, its response written to {@code sink}. */
    private AtmosphereResource suspendedPoll(AtmosphereResource.TRANSPORT transport, List<String> steps,
                                             java.io.StringWriter sink) {
        return suspendedPoll(transport, steps, sink, "poll-" + transport);
    }

    /** As above, under tracking id {@code uuid}. */
    private AtmosphereResource suspendedPoll(AtmosphereResource.TRANSPORT transport, List<String> steps,
                                             java.io.StringWriter sink, String uuid) {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        var response = Mockito.mock(AtmosphereResponse.class);
        var printWriter = new java.io.PrintWriter(sink) {
            @Override public void flush() {
                super.flush();
                steps.add("flush");
            }
        };
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(handle.runId());
        when(request.getAttribute("ai.userId")).thenReturn("alice");
        when(resource.uuid()).thenReturn(uuid);
        when(resource.transport()).thenReturn(transport);
        when(resource.isSuspended()).thenReturn(true);
        when(resource.resume()).thenAnswer(inv -> {
            steps.add("resume");
            return resource;
        });
        try {
            when(resource.getResponse()).thenReturn(response);
            when(response.getWriter()).thenReturn(printWriter);
        } catch (java.io.IOException e) {
            throw new AssertionError("mock cannot throw", e);
        }
        return resource;
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
