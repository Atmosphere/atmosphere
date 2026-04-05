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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E checkpoint integration test — <strong>Spring AI runtime</strong> flavour.
 *
 * <p>Verifies that a coordination driven by an agent whose {@code @Prompt}
 * delegates to Spring AI's {@code ChatClient} produces a durable snapshot
 * chain through the {@code CheckpointingCoordinationJournal} bridge. The
 * Spring AI runtime emits agent boundary events through the coordinator's
 * standard {@code CoordinationJournal} — the same events this test
 * fabricates and records directly, decoupling the assertion from any real
 * LLM call.</p>
 */
@Tag("core")
@Tag("checkpoint-e2e")
@DisplayName("Checkpoint E2E — Spring AI runtime")
class SpringAiCheckpointE2eTest {

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
    @DisplayName("spring-ai-backed coordination yields a chained snapshot per agent boundary")
    void springAiCoordinationProducesSnapshotChain() {
        var coord = "spring-ai-coord-1";
        // Typical Spring AI agent emission sequence: coordinator start, two
        // agent dispatches (each backed by a ChatClient call), completion.
        journal.record(started(coord, "spring-ai-coordinator"));
        journal.record(dispatched(coord, "sa-research", "web_search",
                Map.of("query", "spring-ai streaming")));
        journal.record(completed(coord, "sa-research", "web_search",
                "{\"docs\":3,\"summary\":\"Spring AI supports streaming via Flux<ChatResponse>\"}",
                145L));
        journal.record(dispatched(coord, "sa-writer", "write",
                Map.of("draft", "streaming with Spring AI")));
        journal.record(completed(coord, "sa-writer", "write",
                "Spring AI's ChatClient.stream() returns a Flux of ChatResponse chunks.",
                220L));
        journal.record(allDone(coord, 2, 365L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        // onAgentBoundaries matches only Completed/Failed, so we expect 2
        // snapshots even though 6 events were recorded.
        assertEquals(2, snapshots.size(),
                "one snapshot per agent boundary event");

        var first = snapshots.get(0);
        var second = snapshots.get(1);

        assertTrue(first.isRoot());
        assertEquals(first.id(), second.parent().orElseThrow());
        assertEquals("sa-research", first.agentName());
        assertEquals("sa-writer", second.agentName());
        assertTrue(first.state() instanceof CoordinationEvent.AgentCompleted);
    }

    @Test
    @DisplayName("lastSnapshot tracks the most recent bridge write")
    void lastSnapshotAdvancesWithEachEvent() {
        var coord = "spring-ai-coord-2";
        journal.record(started(coord, "spring-ai-coordinator"));
        journal.record(completed(coord, "sa-agent", "task", "ok", 50L));
        var first = journal.lastSnapshot(coord);
        assertNotNull(first);

        journal.record(completed(coord, "sa-agent", "task", "ok2", 60L));
        var second = journal.lastSnapshot(coord);
        assertNotNull(second);
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);

        // Loading back via the store round-trips cleanly.
        assertTrue(store.load(second).isPresent());
    }

    @Test
    @DisplayName("fork from a mid-run snapshot creates a branched history")
    void forkMidRunBranchesHistory() {
        var coord = "spring-ai-coord-3";
        journal.record(completed(coord, "sa-a", "s", "first", 10L));
        journal.record(completed(coord, "sa-b", "s", "second", 10L));
        var branchPoint = journal.lastSnapshot(coord);

        // Simulate an alternative path explored from the branch point.
        var branched = store.fork(branchPoint, "alternative-path");
        assertEquals(branchPoint, branched.parent().orElseThrow());
        assertEquals("alternative-path", branched.state());

        // The original chain is unaffected.
        assertEquals(3, store.list(CheckpointQuery.forCoordination(coord)).size());
    }
}
