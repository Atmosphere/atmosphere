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
package org.atmosphere.checkpoint.coordinator;

import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointingCoordinationJournalTest {

    private InMemoryCheckpointStore store;
    private InMemoryCoordinationJournal underlying;
    private CheckpointingCoordinationJournal<CoordinationEvent> journal;

    @BeforeEach
    void setUp() {
        store = new InMemoryCheckpointStore();
        underlying = new InMemoryCoordinationJournal();
        journal = new CheckpointingCoordinationJournal<>(
                underlying,
                store,
                CheckpointingCoordinationJournal.onEveryEvent(),
                CoordinationStateExtractors.event());
        journal.start();
    }

    @AfterEach
    void tearDown() {
        journal.stop();
    }

    @Test
    void delegatesRecordToUnderlyingJournal() {
        var started = new CoordinationEvent.CoordinationStarted("coord-1", "ceo", Instant.now());
        journal.record(started);

        var events = underlying.retrieve("coord-1");
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof CoordinationEvent.CoordinationStarted);
    }

    @Test
    void persistsSnapshotForMatchingEvent() {
        var completed = new CoordinationEvent.AgentCompleted(
                "coord-1", "research", "web_search", "found it", Duration.ofMillis(50), Instant.now());
        journal.record(completed);

        var snapshots = store.list(CheckpointQuery.forCoordination("coord-1"));
        assertEquals(1, snapshots.size());
        assertEquals("research", snapshots.get(0).agentName());
        assertTrue(snapshots.get(0).state() instanceof CoordinationEvent.AgentCompleted);
    }

    @Test
    void buildsParentChainAcrossEventsInSameCoordination() {
        var e1 = new CoordinationEvent.AgentDispatched(
                "coord-1", "research", "web_search", Map.of(), Instant.now());
        var e2 = new CoordinationEvent.AgentCompleted(
                "coord-1", "research", "web_search", "ok", Duration.ofMillis(5), Instant.now());
        var e3 = new CoordinationEvent.AgentDispatched(
                "coord-1", "writer", "write", Map.of(), Instant.now());

        journal.record(e1);
        journal.record(e2);
        journal.record(e3);

        var snapshots = store.list(CheckpointQuery.forCoordination("coord-1"));
        assertEquals(3, snapshots.size());

        // First is root, later ones reference their predecessor.
        WorkflowSnapshot<?> first = snapshots.get(0);
        WorkflowSnapshot<?> second = snapshots.get(1);
        WorkflowSnapshot<?> third = snapshots.get(2);

        assertTrue(first.isRoot());
        assertEquals(first.id(), second.parent().orElseThrow());
        assertEquals(second.id(), third.parent().orElseThrow());
    }

    @Test
    void separateCoordinationsHaveIndependentChains() {
        journal.record(new CoordinationEvent.AgentDispatched(
                "coord-1", "a", "s", Map.of(), Instant.now()));
        journal.record(new CoordinationEvent.AgentDispatched(
                "coord-2", "b", "s", Map.of(), Instant.now()));

        var c1 = store.list(CheckpointQuery.forCoordination("coord-1"));
        var c2 = store.list(CheckpointQuery.forCoordination("coord-2"));
        assertEquals(1, c1.size());
        assertEquals(1, c2.size());
        assertTrue(c1.get(0).isRoot());
        assertTrue(c2.get(0).isRoot());
    }

    @Test
    void onAgentBoundariesFilterOnlyMatchesCompletedOrFailed() {
        var agentBoundary = new CheckpointingCoordinationJournal<>(
                new InMemoryCoordinationJournal(),
                store,
                CheckpointingCoordinationJournal.onAgentBoundaries(),
                CoordinationStateExtractors.eventSummary());
        agentBoundary.start();
        try {
            agentBoundary.record(new CoordinationEvent.CoordinationStarted(
                    "c", "ceo", Instant.now()));
            agentBoundary.record(new CoordinationEvent.AgentDispatched(
                    "c", "a", "s", Map.of(), Instant.now()));
            agentBoundary.record(new CoordinationEvent.AgentCompleted(
                    "c", "a", "s", "ok", Duration.ofMillis(1), Instant.now()));
            agentBoundary.record(new CoordinationEvent.AgentFailed(
                    "c", "b", "s", "err", Duration.ofMillis(1), Instant.now()));
            agentBoundary.record(new CoordinationEvent.CoordinationCompleted(
                    "c", Duration.ofMillis(5), 2, Instant.now()));

            // Only Completed + Failed should have produced snapshots.
            var snapshots = store.list(CheckpointQuery.forCoordination("c"));
            assertEquals(2, snapshots.size());
        } finally {
            agentBoundary.stop();
        }
    }

    @Test
    void lastSnapshotReflectsMostRecentEvent() {
        assertNull(journal.lastSnapshot("c"));
        journal.record(new CoordinationEvent.AgentDispatched(
                "c", "a", "s", Map.of(), Instant.now()));
        var firstId = journal.lastSnapshot("c");
        assertNotNull(firstId);

        journal.record(new CoordinationEvent.AgentCompleted(
                "c", "a", "s", "ok", Duration.ofMillis(1), Instant.now()));
        var secondId = journal.lastSnapshot("c");
        assertNotNull(secondId);

        // lastSnapshot advanced
        org.junit.jupiter.api.Assertions.assertNotEquals(firstId, secondId);
    }

    @Test
    void stopClearsInternalState() {
        journal.record(new CoordinationEvent.AgentDispatched(
                "c", "a", "s", Map.of(), Instant.now()));
        assertNotNull(journal.lastSnapshot("c"));
        journal.stop();
        assertNull(journal.lastSnapshot("c"));
    }

    @Test
    void queryDelegatesToUnderlyingJournal() {
        journal.record(new CoordinationEvent.CoordinationStarted(
                "c", "ceo", Instant.now()));
        journal.record(new CoordinationEvent.AgentDispatched(
                "c", "a", "s", Map.of(), Instant.now()));
        List<CoordinationEvent> events = journal.retrieve("c");
        assertEquals(2, events.size());
    }
}
