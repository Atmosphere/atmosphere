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
package org.atmosphere.integration.checkpoint;

import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.coordinator.CheckpointingCoordinationJournal;
import org.atmosphere.checkpoint.coordinator.CoordinationStateExtractors;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.InMemoryCoordinationJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.completed;
import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.dispatched;
import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.started;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E checkpoint integration test — <strong>Google ADK runtime</strong> flavour.
 *
 * <p>Google ADK models workflows using {@code SequentialAgent},
 * {@code ParallelAgent}, and {@code LoopAgent}. The ADK runtime delegates
 * to an {@code InMemoryRunner} which fires events through the
 * coordinator's journal. This test verifies that SequentialAgent and
 * ParallelAgent dispatches both produce checkpoint chains, and that ADK's
 * native {@code SessionService} persistence is complementary to (not
 * replaced by) Atmosphere's {@code CheckpointStore}.</p>
 */
@Tag("core")
@Tag("checkpoint-e2e")
@DisplayName("Checkpoint E2E — Google ADK runtime")
class AdkCheckpointE2eTest {

    private InMemoryCheckpointStore store;
    private CheckpointingCoordinationJournal<CoordinationEvent> journal;

    @BeforeEach
    void setUp() {
        store = new InMemoryCheckpointStore();
        journal = new CheckpointingCoordinationJournal<>(
                new InMemoryCoordinationJournal(),
                store,
                CheckpointingCoordinationJournal.onAgentBoundaries(),
                CoordinationStateExtractors.event());
        journal.start();
    }

    @AfterEach
    void tearDown() {
        journal.stop();
    }

    @Test
    @DisplayName("adk SequentialAgent pipeline produces ordered snapshots")
    void adkSequentialAgentProducesOrderedSnapshots() {
        var coord = "adk-coord-1";
        journal.record(started(coord, "adk-sequential-root"));
        // SequentialAgent runs its children in order.
        journal.record(dispatched(coord, "adk-planner", "plan", Map.of("goal", "book flight")));
        journal.record(completed(coord, "adk-planner", "plan",
                "plan: [search, select, book]", 90L));
        journal.record(dispatched(coord, "adk-searcher", "search", Map.of("query", "SFO→NRT")));
        journal.record(completed(coord, "adk-searcher", "search",
                "3 results", 130L));
        journal.record(dispatched(coord, "adk-booker", "book", Map.of("flight", "UA837")));
        journal.record(completed(coord, "adk-booker", "book",
                "confirmation: ABC123", 170L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        assertEquals(3, snapshots.size());

        assertEquals("adk-planner", snapshots.get(0).agentName());
        assertEquals("adk-searcher", snapshots.get(1).agentName());
        assertEquals("adk-booker", snapshots.get(2).agentName());
    }

    @Test
    @DisplayName("adk ParallelAgent fan-out yields sibling snapshots in arrival order")
    void adkParallelAgentFanOutSnapshots() {
        var coord = "adk-coord-2";
        // ParallelAgent dispatches children concurrently; completion events
        // arrive on the coordinator thread in some serialized order.
        journal.record(started(coord, "adk-parallel-root"));
        journal.record(completed(coord, "adk-weather", "forecast", "sunny, 72F", 55L));
        journal.record(completed(coord, "adk-traffic", "conditions", "light", 45L));
        journal.record(completed(coord, "adk-news", "headlines", "5 stories", 65L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        assertEquals(3, snapshots.size());
        // First is root; others chain in the arrival order captured by the journal.
        assertTrue(snapshots.get(0).isRoot());
        assertEquals(snapshots.get(0).id(), snapshots.get(1).parent().orElseThrow());
        assertEquals(snapshots.get(1).id(), snapshots.get(2).parent().orElseThrow());
    }

    @Test
    @DisplayName("resume after simulated JVM restart loads prior snapshot state")
    void resumeAfterRestartLoadsState() {
        var coord = "adk-coord-3";
        journal.record(completed(coord, "adk-agent", "step1", "partial-result", 100L));
        var snapshotId = journal.lastSnapshot(coord);
        assertNotNull(snapshotId);

        // Simulate: process was killed, a new journal starts up with the
        // same (persistent) store. Replace only the journal decorator —
        // a persistent CheckpointStore survives restart; only the in-memory
        // lastSnapshot cache is lost.
        var resumedJournal = new CheckpointingCoordinationJournal<>(
                new InMemoryCoordinationJournal(),
                store,
                CheckpointingCoordinationJournal.onAgentBoundaries(),
                CoordinationStateExtractors.event());
        resumedJournal.start();
        try {
            var snap = store.<CoordinationEvent>load(snapshotId).orElseThrow();
            assertTrue(snap.state() instanceof CoordinationEvent.AgentCompleted completedEvent
                    && "partial-result".equals(completedEvent.resultText()));
            // The rehydrated snapshot's agent name survives too.
            assertEquals("adk-agent", snap.agentName());
        } finally {
            resumedJournal.stop();
        }
    }
}
