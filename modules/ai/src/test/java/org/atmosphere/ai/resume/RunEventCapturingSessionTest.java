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

import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Proves the producer side of the reattach wire: every text / complete /
 * error event emitted on a {@link RunEventCapturingSession} lands in the
 * underlying {@link RunEventReplayBuffer}, which is the prerequisite for
 * {@link RunReattachSupport#replayPendingRun} to have anything to drain
 * on reconnect. Without this wire the reattach primitive is half-shipped
 * — {@code X-Atmosphere-Run-Id} reconnects replay zero events.
 */
class RunEventCapturingSessionTest {

    @Test
    void sendCapturesTextEventsInOrder() {
        var buffer = new RunEventReplayBuffer();
        var session = new RunEventCapturingSession(new CollectingSession(), buffer);

        session.send("Hello, ");
        session.send("world!");
        session.complete("done");

        var replay = buffer.snapshot();
        assertEquals(3, replay.size(),
                "every send / complete on the wrapper must land in the buffer");
        assertEquals("streaming-text", replay.get(0).type(),
                "capture must use the wire-protocol type name so the replay "
                + "path emits a valid AiStreamMessage JSON frame without translation");
        assertEquals("Hello, ", replay.get(0).payload());
        assertEquals("world!", replay.get(1).payload());
        assertEquals("complete", replay.get(2).type());
        assertEquals("done", replay.get(2).payload());
    }

    @Test
    void emptySendIsNotCaptured() {
        // A runtime that emits an empty partial (whitespace stripping, end
        // of buffer) shouldn't consume a buffer slot — the capture side
        // filters out empty text frames so the bounded capacity favours
        // real payload events.
        var buffer = new RunEventReplayBuffer();
        var session = new RunEventCapturingSession(new CollectingSession(), buffer);

        session.send("");
        session.send(null);
        session.send("actual");

        var replay = buffer.snapshot();
        assertEquals(1, replay.size(), "only the non-empty send is captured");
        assertEquals("actual", replay.get(0).payload());
    }

    @Test
    void errorCapturesTerminalMarker() {
        var buffer = new RunEventReplayBuffer();
        var session = new RunEventCapturingSession(new CollectingSession(), buffer);

        session.send("partial");
        session.error(new RuntimeException("wire dropped"));

        var replay = buffer.snapshot();
        assertEquals(2, replay.size());
        assertEquals("error", replay.get(1).type());
        assertEquals("wire dropped", replay.get(1).payload());
    }

    /**
     * Regression for the P1 terminal-path gap — {@code AiEndpointHandler}
     * must route timeout / exception terminal calls through the
     * capturing session, not the underlying delegate. Otherwise a client
     * that reconnects after the @Prompt method threw or timed out replays
     * partial text with no error envelope and hangs waiting for a
     * terminator that never arrives.
     */
    /**
     * Regression for the {@code handoff()} forwarding hotfix. The
     * default {@link org.atmosphere.ai.StreamingSession#handoff}
     * implementation throws {@code UnsupportedOperationException}; a
     * decorator that fails to forward silently shadows agent-backed
     * sessions and breaks the orchestration-primitives flow. Pin it.
     */
    @Test
    void handoffForwardsToDelegate() {
        var delegate = new HandoffTrackingSession();
        var session = new RunEventCapturingSession(delegate, new RunEventReplayBuffer());
        session.handoff("billing-agent", "show me a refund option");
        assertEquals("billing-agent", delegate.lastAgent,
                "handoff must forward to the delegate — the default interface "
                + "implementation throws UnsupportedOperationException, which "
                + "would break every agent-backed session wrapped by the "
                + "capturing decorator");
        assertEquals("show me a refund option", delegate.lastMessage);
    }

    private static final class HandoffTrackingSession implements org.atmosphere.ai.StreamingSession {
        volatile String lastAgent;
        volatile String lastMessage;
        @Override public String sessionId() { return "t"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
        @Override
        public void handoff(String agentName, String message) {
            this.lastAgent = agentName;
            this.lastMessage = message;
        }
    }

    @Test
    void captureFeedsReattachWithErrorEnvelopeWhenHandlerThrows() {
        var registry = new RunRegistry();
        var handle = registry.register("agent-a", "alice", "sess-1",
                new ExecutionHandle.Settable(() -> { }));
        var session = new RunEventCapturingSession(new CollectingSession(), handle.replayBuffer());

        session.send("partial response");
        // AiEndpointHandler's catch block and timeout VT now call
        // capturingSession.error(...) — simulate that terminal path.
        session.error(new java.util.concurrent.TimeoutException(
                "Prompt processing timed out after 30000ms"));

        // Reconnect and assert the replay payload ends with an error
        // envelope the client can correlate to turn termination.
        var writes = new ArrayList<String>();
        var resource = mockReconnectResource(handle.runId(), writes);
        var replayed = RunReattachSupport.replayPendingRun(resource, registry);
        assertEquals(2, replayed, "partial text + error envelope must both replay");
        assertEquals(1, writes.size());
        var lines = writes.get(0).split("\\n");
        assertEquals(2, lines.length);
        assertTrue(lines[1].contains("\"type\":\"error\""),
                "terminal error envelope must be emitted so client can stop "
                + "spinning after replay — got: " + lines[1]);
        assertTrue(lines[1].contains("timed out"),
                "error envelope must carry the cause message so the client "
                + "can surface a meaningful failure, got: " + lines[1]);
    }

    /**
     * Full loop: capture → disconnect → reconnect → replay. Simulates
     * the mid-stream disconnect scenario by routing
     * {@link RunReattachSupport#replayPendingRun} against the buffer the
     * capturing session populated. This is the unit-level proof of the
     * end-to-end reattach wire that the Playwright spec documents.
     */
    @Test
    void captureFeedsReattachReplay() {
        var registry = new RunRegistry();
        var handle = registry.register("agent-a", "alice", "sess-1",
                new ExecutionHandle.Settable(() -> { }));

        // Wrap a live session with the capture decorator — mirrors what
        // AiEndpointHandler does after registering the run.
        var session = new RunEventCapturingSession(new CollectingSession(), handle.replayBuffer());
        session.send("Streamed ");
        session.send("tokens");
        session.complete();
        // Client disconnects mid-stream — nothing further happens here.

        // Reconnect: carry the run id as the request attribute and route
        // through RunReattachSupport like AiEndpointHandler.onReady does.
        var writes = new ArrayList<String>();
        var resource = mockReconnectResource(handle.runId(), writes);
        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(3, replayed,
                "three captured events (2 text + 1 complete) must replay on reconnect");
        assertEquals(1, writes.size(),
                "replay batches into a single write — long-polling flushes and "
                + "resumes on the first resource.write, so per-event writes drop "
                + "everything after the first");
        var lines = writes.get(0).split("\\n");
        assertEquals(3, lines.length, "batched write must contain one JSON line per event");
        for (var line : lines) {
            assertTrue(line.startsWith("{") && line.endsWith("}"),
                    "each replay frame must be a JSON AiStreamMessage envelope, got: "
                    + line);
        }
        assertTrue(lines[0].contains("\"type\":\"streaming-text\"")
                && lines[0].contains("\"data\":\"Streamed \""),
                "first replay frame must be a streaming-text envelope carrying the "
                + "original payload, got: " + lines[0]);
        assertTrue(lines[1].contains("\"data\":\"tokens\""),
                "second frame must carry the second captured payload: " + lines[1]);
        assertTrue(lines[2].contains("\"type\":\"complete\""),
                "terminal frame must be a complete envelope: " + lines[2]);
    }

    @SuppressWarnings("MockitoMockClassCanBeFinal")
    private static AtmosphereResource mockReconnectResource(
            String runId, List<String> writes) {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        var response = Mockito.mock(org.atmosphere.cpr.AtmosphereResponse.class);
        var stringWriter = new java.io.StringWriter();
        var printWriter = new java.io.PrintWriter(stringWriter) {
            @Override public void flush() {
                super.flush();
                writes.clear();
                writes.add(stringWriter.toString());
            }
        };
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE))
                .thenReturn(runId);
        when(resource.uuid()).thenReturn("reconnect-res");
        try {
            when(resource.getResponse()).thenReturn(response);
            when(response.getWriter()).thenReturn(printWriter);
        } catch (java.io.IOException e) {
            throw new AssertionError("mock cannot throw", e);
        }
        return resource;
    }
}
