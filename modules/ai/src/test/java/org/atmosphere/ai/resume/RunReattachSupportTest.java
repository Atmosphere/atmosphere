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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
        handle.replayBuffer().capture("text", "Hello, ");
        handle.replayBuffer().capture("text", "how can I help?");
        handle.replayBuffer().capture("complete", "[complete]");

        var writes = new ArrayList<String>();
        var resource = mockResourceWithRunId(handle.runId(), null, writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(3, replayed, "all three buffered events must reach the new resource");
        assertEquals(List.of("Hello, ", "how can I help?", "[complete]"), writes,
                "replay must preserve capture order — clients reconstruct the stream");
    }

    @Test
    void replayFallsBackToHeaderWhenAttributeIsAbsent() {
        handle.replayBuffer().capture("text", "raw-client frame");
        var writes = new ArrayList<String>();
        // Null attribute, run id only in the HTTP header — raw WS clients
        // bypass DurableSessionInterceptor but still carry the id.
        var resource = mockResourceWithRunId(null, handle.runId(), writes);

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(1, replayed,
                "X-Atmosphere-Run-Id header must be accepted when the interceptor "
                + "attribute is absent — raw-WebSocket clients rely on it");
    }

    @Test
    void replayStopsOnWriteFailureAndReportsPartialCount() {
        handle.replayBuffer().capture("text", "first");
        handle.replayBuffer().capture("text", "second — write will throw");
        handle.replayBuffer().capture("text", "third never attempted");

        var writes = new ArrayList<String>();
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE))
                .thenReturn(handle.runId());
        when(resource.uuid()).thenReturn("res-1");
        // Throw on the second write to exercise the partial-count path.
        Mockito.when(resource.write(anyString())).thenAnswer(inv -> {
            var arg = (String) inv.getArgument(0);
            if ("second — write will throw".equals(arg)) {
                throw new RuntimeException("socket closed");
            }
            writes.add(arg);
            return resource;
        });

        var replayed = RunReattachSupport.replayPendingRun(resource, registry);

        assertEquals(1, replayed,
                "replay must report the partial count when a write fails "
                + "mid-drain — callers can decide whether to retry");
        assertEquals(List.of("first"), writes,
                "events after the failure must not be written (stream is terminal)");
    }

    private static AtmosphereResource mockResourceWithRunId(
            String attributeValue, String headerValue, List<String> writes) {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE))
                .thenReturn(attributeValue);
        when(request.getHeader(RunReattachSupport.RUN_ID_HEADER))
                .thenReturn(headerValue);
        when(resource.uuid()).thenReturn("res-" + System.identityHashCode(resource));
        Mockito.when(resource.write(anyString())).thenAnswer(inv -> {
            writes.add(inv.getArgument(0));
            return resource;
        });
        return resource;
    }
}
