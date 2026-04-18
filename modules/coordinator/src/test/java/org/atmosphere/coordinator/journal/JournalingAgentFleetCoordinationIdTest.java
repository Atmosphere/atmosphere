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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the round-2 follow-up on the 4.0.37 journal-bridge
 * fix: {@link JournalingAgentFleet#coordinationId()} must propagate the
 * {@code @Coordinator(name=...)} value so downstream consumers (e.g.
 * {@code GET /api/checkpoints?coordination=dispatch}) match snapshots by
 * the coordinator's logical name rather than a per-invocation UUID.
 *
 * <p>Pre-fix behaviour: every coordination generated a fresh
 * {@code UUID.randomUUID()} and the REST filter matched none of them.</p>
 */
class JournalingAgentFleetCoordinationIdTest {

    private InMemoryCoordinationJournal journal;
    private DefaultAgentFleet delegate;

    @BeforeEach
    void setUp() {
        journal = new InMemoryCoordinationJournal();
        journal.start();

        var transport = mock(AgentTransport.class);
        when(transport.isAvailable()).thenReturn(true);
        when(transport.send(any(), any(), any())).thenReturn(
                new AgentResult("weather", "forecast", "Sunny 72F",
                        Map.of(), Duration.ofMillis(10), true));

        var proxies = new LinkedHashMap<String, AgentProxy>();
        proxies.put("weather", new DefaultAgentProxy(
                "weather", "1.0.0", 1, true, transport));
        delegate = new DefaultAgentFleet(proxies);
    }

    @Test
    void coordinatorNameBecomesCanonicalCoordinationId() {
        // Given a JournalingAgentFleet constructed with the logical
        // coordinator name "dispatch" (matching the @Coordinator(name="...")
        // value used in spring-boot-checkpoint-agent), every recorded event
        // must carry "dispatch" as its coordinationId — not a random UUID.
        var fleet = new JournalingAgentFleet(delegate, journal, "dispatch");

        fleet.parallel(new AgentCall("weather", "forecast",
                Map.of("city", "Madrid")));

        var allEvents = journal.query(CoordinationQuery.all());
        assertFalse(allEvents.isEmpty(),
                "parallel() must record at least Started + Dispatched + "
                        + "Completed + Completed events");

        for (var event : allEvents) {
            assertEquals("dispatch", event.coordinationId(),
                    "Event " + event.getClass().getSimpleName() + " must carry "
                            + "the coordinator name as its coordinationId");
        }
    }

    @Test
    void retrieveByCoordinatorNameReturnsAllEventsForThatCoordinator() {
        // The REST filter `?coordination=dispatch` lands in
        // CheckpointStore.list(CheckpointQuery.coordinationId("dispatch"));
        // the journal-side analogue is retrieve("dispatch"). If the
        // coordinationId is a UUID this returns nothing; if it's the
        // coordinator name it returns every recorded event.
        var fleet = new JournalingAgentFleet(delegate, journal, "dispatch");
        fleet.parallel(new AgentCall("weather", "forecast", Map.of()));

        var byName = journal.retrieve("dispatch");
        assertFalse(byName.isEmpty(),
                "retrieve(coordinatorName) must return events after the "
                        + "fix — pre-fix this returned an empty list because "
                        + "events were keyed by UUID");
    }

    @Test
    void multipleInvocationsOfTheSameCoordinatorReuseTheCanonicalId() {
        // Users who drive the same coordinator twice — directly or after a
        // JVM restart — expect both runs to be discoverable via the same
        // REST filter. The canonical coordinator-name id is what makes
        // that work: two invocations, one logical group.
        var fleet = new JournalingAgentFleet(delegate, journal, "dispatch");

        fleet.parallel(new AgentCall("weather", "forecast", Map.of()));
        fleet.pipeline(new AgentCall("weather", "forecast", Map.of()));

        var ids = journal.query(CoordinationQuery.all()).stream()
                .map(CoordinationEvent::coordinationId)
                .distinct()
                .toList();
        assertEquals(1, ids.size(),
                "Two invocations of the same coordinator must share one "
                        + "canonical coordinationId so REST filter matches both");
        assertEquals("dispatch", ids.get(0));
    }

    @Test
    void nullCoordinatorNameFallsBackToUuid() {
        // Defensive path: if someone instantiates JournalingAgentFleet
        // without a coordinator name (ad-hoc tests, third-party decorators),
        // the pre-fix UUID behaviour must remain so the decorator never
        // crashes with a NullPointerException inside CheckpointStore.save().
        var fleet = new JournalingAgentFleet(delegate, journal, null);
        fleet.parallel(new AgentCall("weather", "forecast", Map.of()));

        var events = journal.query(CoordinationQuery.all());
        assertFalse(events.isEmpty());
        var fallbackId = events.get(0).coordinationId();
        assertNotNull(fallbackId, "fallback id must not be null");
        assertFalse(fallbackId.isEmpty(), "fallback id must not be empty");

        // The fallback must be a well-formed UUID — parsing throws if not.
        var parsed = UUID.fromString(fallbackId);
        assertNotNull(parsed);
    }

    @Test
    void emptyCoordinatorNameFallsBackToUuid() {
        // Matches the null case: an empty string is not a meaningful
        // canonical id and the decorator must not write empty-string
        // coordination ids into the store (that breaks REST filter
        // semantics and the SqliteCheckpointStore primary key).
        var fleet = new JournalingAgentFleet(delegate, journal, "");
        fleet.parallel(new AgentCall("weather", "forecast", Map.of()));

        var events = journal.query(CoordinationQuery.all());
        assertFalse(events.isEmpty());
        var fallbackId = events.get(0).coordinationId();
        assertNotNull(fallbackId);
        assertFalse(fallbackId.isEmpty());
        // Parses as UUID — proves it's the fallback path.
        var parsed = UUID.fromString(fallbackId);
        assertNotNull(parsed);
    }

    @Test
    void directAgentCallCarriesCoordinatorNameAsCoordinationId() {
        // The single-call path (fleet.agent("x").call(...)) must also
        // propagate the coordinator name — the JournalingAgentProxy
        // snapshots the id at agent(...) time, so if that snapshot is
        // a UUID, the whole proxy chain records the wrong id.
        var fleet = new JournalingAgentFleet(delegate, journal, "dispatch");
        var proxy = fleet.agent("weather");
        proxy.call("forecast", Map.of("city", "Paris"));

        var events = journal.query(CoordinationQuery.all());
        assertTrue(events.stream()
                        .allMatch(e -> "dispatch".equals(e.coordinationId())),
                "JournalingAgentProxy must snapshot the coordinator name, "
                        + "not a per-invocation UUID");
    }

    @Test
    void directAgentCallLeavesNoThreadLocalResidueOnServletPool() throws Exception {
        // Regression: prior to the fix, coordinationId() stashed the id into a
        // ThreadLocal on the agent(name).call() path and never removed it.
        // On a servlet thread pool this pinned "dispatch" to the worker and
        // bled into unrelated subsequent requests served on the same thread.
        // With the ThreadLocal removed, inspecting the instance via reflection
        // must show zero ThreadLocal-typed fields.
        var fleet = new JournalingAgentFleet(delegate, journal, "dispatch");
        fleet.agent("weather").call("forecast", Map.of("city", "Paris"));

        for (var f : JournalingAgentFleet.class.getDeclaredFields()) {
            assertFalse(ThreadLocal.class.isAssignableFrom(f.getType()),
                    "JournalingAgentFleet must not carry a ThreadLocal field — "
                            + "servlet pools would pin the coordination id across requests: "
                            + f.getName());
        }

        // And: a second fleet with a different coordinator name, invoked on
        // the same thread, must surface its own id — proving no state from
        // the first call survives on this thread.
        var other = new JournalingAgentFleet(delegate, journal, "reporter");
        other.agent("weather").call("forecast", Map.of());

        var reporterEvents = journal.retrieve("reporter");
        assertFalse(reporterEvents.isEmpty(),
                "second fleet must publish under its own coordinator name, "
                        + "not the first fleet's stale ThreadLocal value");
        assertTrue(reporterEvents.stream()
                        .allMatch(e -> "reporter".equals(e.coordinationId())),
                "all events for 'reporter' must carry 'reporter', not 'dispatch'");
    }

    @Test
    void distinctCoordinatorsProduceDistinctCanonicalIds() {
        // Two separate JournalingAgentFleet instances wrapping the same
        // underlying delegate must each emit their own coordinator-name id.
        var fleetA = new JournalingAgentFleet(delegate, journal, "dispatch");
        var fleetB = new JournalingAgentFleet(delegate, journal, "reporter");

        fleetA.parallel(new AgentCall("weather", "forecast", Map.of()));
        fleetB.parallel(new AgentCall("weather", "forecast", Map.of()));

        assertFalse(journal.retrieve("dispatch").isEmpty(),
                "fleetA must publish events under 'dispatch'");
        assertFalse(journal.retrieve("reporter").isEmpty(),
                "fleetB must publish events under 'reporter'");
        assertNotEquals(journal.retrieve("dispatch").size(),
                0, "non-empty guard");
    }
}
