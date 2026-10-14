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
package org.atmosphere.admin.coordinator;

import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the read path behind {@code /api/admin/journal} and {@code /api/admin/flow}:
 * the admin endpoints must report the events in the journals the coordinator
 * fleets actually record into. The regression class: the admin side was wired
 * with {@link CoordinationJournal#NOOP} (or a Spring bean lookup) while
 * {@code CoordinatorProcessor} resolved the real journal through
 * {@code ServiceLoader}, so the journal endpoint returned {@code []} and the flow
 * graph had no nodes after a run the console showed in full.
 */
class FleetCoordinationJournalTest {

    private static final Instant T0 = Instant.parse("2026-10-14T16:00:00Z");

    @Test
    void readsTheJournalAFleetRecordsInto() {
        var journal = journalWith("coord-1", "research-agent", T0);
        var view = new FleetCoordinationJournal(() -> Map.of("ceo", fleet(journal)), CoordinationJournal.NOOP);

        var events = view.query(CoordinationQuery.all());

        assertEquals(journal.query(CoordinationQuery.all()), events,
                "a single fleet journal is passed through unchanged");
        assertEquals(2, view.retrieve("coord-1").size());
        assertEquals(2, view.retrieveEnveloped("coord-1").size());
    }

    @Test
    void seesFleetsRegisteredAfterConstruction() {
        var registry = new ConcurrentHashMap<String, AgentFleet>();
        var view = new FleetCoordinationJournal(() -> registry, CoordinationJournal.NOOP);
        assertTrue(view.query(CoordinationQuery.all()).isEmpty());

        registry.put("ceo", fleet(journalWith("coord-1", "research-agent", T0)));

        assertEquals(2, view.query(CoordinationQuery.all()).size());
    }

    @Test
    void sharedJournalIsReadOnceAndSeparateJournalsMergeInTimeOrder() {
        var shared = journalWith("coord-1", "research-agent", T0);
        var later = journalWith("coord-2", "writer-agent", T0.plusSeconds(60));
        var fallback = journalWith("coord-0", "finance-agent", T0.minusSeconds(60));
        var view = new FleetCoordinationJournal(
                () -> Map.of("a", fleet(shared), "b", fleet(shared), "c", fleet(later)), fallback);

        var events = view.query(CoordinationQuery.all());

        assertEquals(6, events.size(), "the shared journal must not be read twice: " + events);
        assertEquals(List.of("coord-0", "coord-0", "coord-1", "coord-1", "coord-2", "coord-2"),
                events.stream().map(CoordinationEvent::coordinationId).toList());
        assertEquals(3, view.query(new CoordinationQuery(null, null, null, null, 3)).size(),
                "the limit applies to the merged result");
    }

    @Test
    void ignoresNoopJournalsAndFailingSuppliers() {
        var noopFleet = fleet(CoordinationJournal.NOOP);
        assertTrue(new FleetCoordinationJournal(() -> Map.of("x", noopFleet), CoordinationJournal.NOOP)
                .query(CoordinationQuery.all()).isEmpty());
        var view = new FleetCoordinationJournal(() -> {
            throw new IllegalStateException("framework not initialized");
        }, journalWith("coord-9", "finance-agent", T0));
        assertEquals(2, view.query(CoordinationQuery.all()).size(),
                "a failing fleet lookup still reads the fallback journal");
    }

    @Test
    void doesNotOwnTheJournalsItReads() {
        var journal = mock(CoordinationJournal.class);
        var view = new FleetCoordinationJournal(() -> Map.of("ceo", fleet(journal)), CoordinationJournal.NOOP);

        view.start();
        view.stop();

        verify(journal, never()).start();
        verify(journal, never()).stop();
        assertThrows(UnsupportedOperationException.class,
                () -> view.record(new CoordinationEvent.CoordinationStarted("c", "ceo", T0)));
    }

    private static InMemoryCoordinationJournal journalWith(String coordinationId, String agent, Instant at) {
        var journal = new InMemoryCoordinationJournal();
        journal.start();
        journal.record(new CoordinationEvent.AgentDispatched(coordinationId, agent, "skill", Map.of(), at));
        journal.record(new CoordinationEvent.AgentCompleted(coordinationId, agent, "skill", "done",
                java.time.Duration.ofMillis(5), at.plusMillis(5)));
        return journal;
    }

    private static AgentFleet fleet(CoordinationJournal journal) {
        var fleet = mock(AgentFleet.class);
        when(fleet.journal()).thenReturn(journal);
        return fleet;
    }
}
