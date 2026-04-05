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

import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.allDone;
import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.completed;
import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.dispatched;
import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.started;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E checkpoint integration test — <strong>JetBrains Koog runtime</strong> flavour.
 *
 * <p>Koog's {@code AIAgentStrategy} DSL produces a graph of nodes;
 * execution of each node maps to a dispatch/complete event pair on the
 * coordinator. This test verifies the checkpoint bridge snapshots a
 * realistic Koog execution (node A → node B subgraph → terminal node)
 * preserving ordering + causal chain.</p>
 */
@Tag("core")
@Tag("checkpoint-e2e")
@DisplayName("Checkpoint E2E — JetBrains Koog runtime")
class KoogCheckpointE2eTest {

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
    @DisplayName("koog graph nodes map to a linear snapshot chain")
    void koogGraphProducesLinearChain() {
        var coord = "koog-coord-1";
        // Koog strategy with 3 sequential nodes.
        journal.record(started(coord, "koog-strategy"));
        journal.record(dispatched(coord, "intent-classifier", "classify",
                Map.of("input", "book a flight to Tokyo")));
        journal.record(completed(coord, "intent-classifier", "classify",
                "intent=book_flight, confidence=0.94", 60L));
        journal.record(dispatched(coord, "slot-filler", "extract",
                Map.of("intent", "book_flight")));
        journal.record(completed(coord, "slot-filler", "extract",
                "destination=Tokyo, departure=?", 80L));
        journal.record(dispatched(coord, "responder", "respond",
                Map.of("slots", "destination=Tokyo")));
        journal.record(completed(coord, "responder", "respond",
                "When would you like to depart?", 110L));
        journal.record(allDone(coord, 3, 250L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        assertEquals(3, snapshots.size(), "one snapshot per node completion");

        // Verify agent names match the Koog graph's node names.
        assertEquals("intent-classifier", snapshots.get(0).agentName());
        assertEquals("slot-filler", snapshots.get(1).agentName());
        assertEquals("responder", snapshots.get(2).agentName());

        // Verify full causal chain.
        assertTrue(snapshots.get(0).isRoot());
        assertEquals(snapshots.get(0).id(), snapshots.get(1).parent().orElseThrow());
        assertEquals(snapshots.get(1).id(), snapshots.get(2).parent().orElseThrow());
    }

    @Test
    @DisplayName("subgraph execution within a koog strategy is fully captured")
    void koogSubgraphExecutionCaptured() {
        var coord = "koog-coord-2";
        // Parent node dispatches two sibling subgraph nodes.
        journal.record(completed(coord, "root-node", "plan", "fanout", 20L));
        journal.record(completed(coord, "sub-a", "exec", "result-a", 40L));
        journal.record(completed(coord, "sub-b", "exec", "result-b", 50L));
        journal.record(completed(coord, "merge", "join", "combined", 10L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        assertEquals(4, snapshots.size());
        // Bridge writes a linear chain even for concurrent nodes (events arrive
        // serially on the coordinator thread).
        assertTrue(snapshots.get(0).isRoot());
        for (int i = 1; i < snapshots.size(); i++) {
            assertEquals(
                    snapshots.get(i - 1).id(),
                    snapshots.get(i).parent().orElseThrow(),
                    "snapshot " + i + " should chain to previous");
        }
    }

    @Test
    @DisplayName("koog-agent snapshots are queryable by agent name")
    void filterByAgentNameIsolatesKoogNodes() {
        var coord = "koog-coord-3";
        journal.record(completed(coord, "intent-classifier", "s", "a", 10L));
        journal.record(completed(coord, "slot-filler", "s", "b", 10L));
        journal.record(completed(coord, "intent-classifier", "s", "c", 10L));

        var classified = store.list(CheckpointQuery.forAgent("intent-classifier"));
        assertEquals(2, classified.size());
        assertTrue(classified.stream()
                .allMatch(s -> "intent-classifier".equals(s.agentName())));
    }
}
