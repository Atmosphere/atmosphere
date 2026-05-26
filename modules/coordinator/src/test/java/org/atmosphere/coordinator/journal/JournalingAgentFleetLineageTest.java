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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Causal-lineage regression suite — verifies every {@link JournalingAgentFleet}
 * dispatch path threads the correct {@code parentEventId} on emitted
 * {@link EventEnvelope}s so consumers can reconstruct the execution DAG from
 * the journal alone (per the event-sourced runtime contract).
 */
class JournalingAgentFleetLineageTest {

    private InMemoryCoordinationJournal journal;
    private JournalingAgentFleet fleet;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();

        AgentTransport alphaTransport = mock(AgentTransport.class);
        AgentTransport betaTransport = mock(AgentTransport.class);
        when(alphaTransport.isAvailable()).thenReturn(true);
        when(betaTransport.isAvailable()).thenReturn(true);
        when(alphaTransport.send(any(), any(), any())).thenReturn(
                new AgentResult("alpha", "do", "ok-alpha", Map.of(), Duration.ofMillis(5), true));
        when(betaTransport.send(any(), any(), any())).thenReturn(
                new AgentResult("beta", "do", "ok-beta", Map.of(), Duration.ofMillis(5), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("alpha", new DefaultAgentProxy("alpha", "1.0.0", 1, true, alphaTransport));
        proxies.put("beta", new DefaultAgentProxy("beta", "1.0.0", 1, true, betaTransport));

        fleet = new JournalingAgentFleet(new DefaultAgentFleet(proxies), journal, "lineage-test");
    }

    @Test
    void parallelEnvelopesFormStartedToDispatchedToCompletedChain() {
        fleet.parallel(
                new AgentCall("alpha", "do", Map.of()),
                new AgentCall("beta", "do", Map.of())
        );

        var envelopes = journal.retrieveEnveloped("lineage-test");
        // Expected: 1 Started + 2 Dispatched + 2 Completed + 1 CoordinationCompleted
        assertEquals(6, envelopes.size(),
                "parallel() should emit started + N dispatched + N completed + completed");

        var started = findFirst(envelopes, CoordinationEvent.CoordinationStarted.class);
        assertNull(started.parentEventId(), "CoordinationStarted must be a root envelope");
        assertNotNull(started.eventId());

        // All AgentDispatched must parent off the CoordinationStarted
        envelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched)
                .forEach(env -> assertEquals(started.eventId(), env.parentEventId(),
                        "AgentDispatched.parentEventId must equal CoordinationStarted.eventId"));

        // Every AgentCompleted must parent off a known AgentDispatched envelope
        var dispatchIds = envelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched)
                .map(EventEnvelope::eventId)
                .collect(java.util.stream.Collectors.toSet());

        envelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentCompleted)
                .forEach(env -> assertTrue(dispatchIds.contains(env.parentEventId()),
                        "AgentCompleted.parentEventId must reference an AgentDispatched envelope"));

        var completed = findFirst(envelopes, CoordinationEvent.CoordinationCompleted.class);
        assertEquals(started.eventId(), completed.parentEventId(),
                "CoordinationCompleted must parent off CoordinationStarted");
    }

    @Test
    void pipelineEnvelopesFormSequentialDispatchCompletedChain() {
        fleet.pipeline(
                new AgentCall("alpha", "do", Map.of()),
                new AgentCall("beta", "do", Map.of())
        );

        var envelopes = journal.retrieveEnveloped("lineage-test");
        var started = findFirst(envelopes, CoordinationEvent.CoordinationStarted.class);

        // Each dispatched/completed pair must form an edge
        var dispatchedEnvs = envelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentDispatched)
                .toList();
        assertEquals(2, dispatchedEnvs.size());

        for (var d : dispatchedEnvs) {
            assertEquals(started.eventId(), d.parentEventId(),
                    "every pipeline dispatch parents off CoordinationStarted");
        }

        // Each AgentCompleted should be a direct child of an AgentDispatched in this coord
        var dispatchIds = new HashSet<>(dispatchedEnvs.stream().map(EventEnvelope::eventId).toList());
        var completedParents = envelopes.stream()
                .filter(env -> env.event() instanceof CoordinationEvent.AgentCompleted)
                .map(EventEnvelope::parentEventId)
                .toList();
        assertEquals(2, completedParents.size());
        for (var p : completedParents) {
            assertTrue(dispatchIds.contains(p),
                    "AgentCompleted.parentEventId must reference an AgentDispatched eventId");
        }
    }

    @Test
    void proxyCallEmitsRootDispatchedFollowedByChildCompleted() {
        fleet.agent("alpha").call("do", Map.of());

        var envelopes = journal.retrieveEnveloped("lineage-test");
        // proxy.call() path: no CoordinationStarted; dispatched is a root, completed is a child
        assertEquals(2, envelopes.size(),
                "proxy.call() should emit 1 dispatched + 1 completed envelope");

        var dispatched = findFirst(envelopes, CoordinationEvent.AgentDispatched.class);
        assertNull(dispatched.parentEventId(),
                "AgentDispatched via proxy.call() is a root in the DAG (no surrounding Started)");

        var completed = findFirst(envelopes, CoordinationEvent.AgentCompleted.class);
        assertEquals(dispatched.eventId(), completed.parentEventId(),
                "AgentCompleted must parent off its matching AgentDispatched");
    }

    @Test
    void routeEmitsRootEnvelope() {
        var input = new AgentResult("alpha", "do", "ok", Map.of(), Duration.ofMillis(5), true);
        fleet.route(input, spec -> spec
                .when(r -> "ok".equals(r.text()),
                        new AgentCall("beta", "do", Map.of()))
                .otherwise(f -> f.agent("alpha").call("do", Map.of())));

        var envelopes = journal.retrieveEnveloped("lineage-test");
        var routeEnv = findFirst(envelopes, CoordinationEvent.RouteEvaluated.class);
        assertNull(routeEnv.parentEventId(),
                "route() without surrounding Started must emit RouteEvaluated as a root envelope");
        assertNotNull(routeEnv.eventId());
    }

    @Test
    void retrieveEnvelopedAndRetrievePreserveSameOrdering() {
        fleet.parallel(new AgentCall("alpha", "do", Map.of()));

        var envelopes = journal.retrieveEnveloped("lineage-test");
        var events = journal.retrieve("lineage-test");
        assertEquals(envelopes.size(), events.size());
        for (int i = 0; i < envelopes.size(); i++) {
            assertEquals(events.get(i), envelopes.get(i).event(),
                    "retrieve() should be a pure projection of retrieveEnveloped()");
        }
    }

    @Test
    void eventIdsAreUnique() {
        fleet.parallel(
                new AgentCall("alpha", "do", Map.of()),
                new AgentCall("beta", "do", Map.of())
        );
        var ids = new HashSet<String>();
        for (var env : journal.retrieveEnveloped("lineage-test")) {
            assertFalse(ids.contains(env.eventId()),
                    "every emitted envelope must have a unique eventId");
            ids.add(env.eventId());
        }
    }

    private static EventEnvelope findFirst(
            java.util.List<EventEnvelope> envelopes,
            Class<? extends CoordinationEvent> type) {
        return envelopes.stream()
                .filter(env -> type.isInstance(env.event()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no envelope found for event type " + type.getSimpleName()));
    }
}
