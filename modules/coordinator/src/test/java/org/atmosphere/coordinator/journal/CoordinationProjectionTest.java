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
package org.atmosphere.coordinator.journal;

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoordinationProjectionTest {

    private InMemoryCoordinationJournal journal;
    private JournalingAgentFleet fleet;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();
        AgentTransport alpha = mock(AgentTransport.class);
        AgentTransport beta = mock(AgentTransport.class);
        when(alpha.isAvailable()).thenReturn(true);
        when(beta.isAvailable()).thenReturn(true);
        when(alpha.send(any(), any(), any())).thenReturn(
                new AgentResult("alpha", "do", "ok", Map.of(), Duration.ofMillis(5), true));
        when(beta.send(any(), any(), any())).thenReturn(
                new AgentResult("beta", "do", "ok", Map.of(), Duration.ofMillis(5), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("alpha", new DefaultAgentProxy("alpha", "1.0.0", 1, true, alpha));
        proxies.put("beta", new DefaultAgentProxy("beta", "1.0.0", 1, true, beta));
        fleet = new JournalingAgentFleet(new DefaultAgentFleet(proxies), journal, "projection-test");
    }

    @Test
    void parallelProjectionHasSingleRootAndCorrectChildren() {
        fleet.parallel(
                new AgentCall("alpha", "do", Map.of()),
                new AgentCall("beta", "do", Map.of())
        );

        var projection = CoordinationProjection.from(journal, "projection-test");

        assertEquals(1, projection.roots().size(), "parallel() should have a single CoordinationStarted root");
        var started = projection.roots().get(0);
        assertTrue(started.event() instanceof CoordinationEvent.CoordinationStarted);

        // Children of started: 2 AgentDispatched + 1 CoordinationCompleted
        var children = projection.children(started.eventId());
        assertEquals(3, children.size());
        long dispatchedKids = children.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched).count();
        long completedKids = children.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.CoordinationCompleted).count();
        assertEquals(2, dispatchedKids);
        assertEquals(1, completedKids);

        // Each AgentDispatched should have one AgentCompleted child
        for (var dispatchedEnv : children) {
            if (dispatchedEnv.event() instanceof CoordinationEvent.AgentDispatched) {
                var grandkids = projection.children(dispatchedEnv.eventId());
                assertEquals(1, grandkids.size(),
                        "each AgentDispatched should have one AgentCompleted child");
                assertTrue(grandkids.get(0).event() instanceof CoordinationEvent.AgentCompleted);
            }
        }
    }

    @Test
    void walkVisitsEveryEnvelopeWithCorrectDepth() {
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));

        var projection = CoordinationProjection.from(journal, "projection-test");
        var visited = new ArrayList<int[]>();
        projection.walk((env, depth) -> visited.add(new int[]{depth, indexOf(env, projection)}));

        // We expect every envelope visited exactly once
        assertEquals(projection.envelopes().size(), visited.size());

        // Root depth must be 0
        assertEquals(0, visited.get(0)[0], "first visited envelope is a root at depth 0");

        // No depth exceeds tree depth (started -> dispatched -> completed = 2)
        for (var pair : visited) {
            assertTrue(pair[0] <= 2,
                    "max DAG depth in parallel-of-1 is 2 (started -> dispatched -> completed)");
        }
    }

    @Test
    void agentsAggregateReturnsParticipants() {
        fleet.parallel(
                new AgentCall("alpha", "do", Map.of()),
                new AgentCall("beta", "do", Map.of())
        );

        var projection = CoordinationProjection.from(journal, "projection-test");
        var agents = projection.agents();
        assertTrue(agents.contains("alpha"));
        assertTrue(agents.contains("beta"));
        assertEquals(2, agents.size());
    }

    @Test
    void emptyCoordinationProducesEmptyProjection() {
        var projection = CoordinationProjection.from(journal, "no-such-coord");
        assertTrue(projection.envelopes().isEmpty());
        assertTrue(projection.roots().isEmpty());
        assertTrue(projection.agents().isEmpty());
        assertFalse(projection.event("anything").isPresent());
    }

    @Test
    void eventLookupByIdRoundTrips() {
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));
        var projection = CoordinationProjection.from(journal, "projection-test");
        for (var env : projection.envelopes()) {
            assertEquals(env, projection.event(env.eventId()).orElseThrow());
        }
    }

    @Test
    void projectionIsImmutable() {
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));
        var projection = CoordinationProjection.from(journal, "projection-test");

        // Subsequent fleet activity should NOT change the projection
        var sizeBefore = projection.envelopes().size();
        fleet.parallel(new AgentCall("beta", "do", Map.of()));
        assertEquals(sizeBefore, projection.envelopes().size());

        // Returned lists are unmodifiable
        var roots = projection.roots();
        try {
            roots.add(EventEnvelope.root(new CoordinationEvent.CoordinationStarted(
                    "x", "x", java.time.Instant.now())));
            throw new AssertionError("expected unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // pass
        }
    }

    private static int indexOf(EventEnvelope env, CoordinationProjection projection) {
        return projection.envelopes().indexOf(env);
    }
}
