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
        assertEquals("text", replay.get(0).type());
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
        assertEquals("Streamed \u001etokens\u001e", writes.get(0),
                "batched payload must preserve capture order with the ASCII "
                + "record-separator (U+001E) between events so the client "
                + "re-parses boundaries without collisions on printable chars");
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
