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
import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.failed;
import static org.atmosphere.integration.checkpoint.CheckpointE2eFixtures.started;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E checkpoint integration test — <strong>LangChain4j runtime</strong> flavour.
 *
 * <p>LangChain4j agents typically use a streaming {@code StreamingChatModel}
 * plus tool-calling via {@code AiServices}. The coordinator records the
 * same agent boundary events regardless of backend, so this test verifies
 * the checkpoint bridge handles a sequence that includes tool-call
 * dispatches, a transient failure, and a retry — a realistic LangChain4j
 * agent execution pattern.</p>
 */
@Tag("core")
@Tag("checkpoint-e2e")
@DisplayName("Checkpoint E2E — LangChain4j runtime")
class LangChain4jCheckpointE2eTest {

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
    @DisplayName("langchain4j tool-calling loop produces snapshot per completion")
    void langChain4jToolLoopSnapshots() {
        var coord = "lc4j-coord-1";
        // LangChain4j agent making multiple tool calls then synthesising.
        journal.record(started(coord, "lc4j-coordinator"));
        journal.record(dispatched(coord, "lc4j-agent", "search",
                Map.of("q", "langchain4j streaming")));
        journal.record(completed(coord, "lc4j-agent", "search",
                "[doc1, doc2, doc3]", 180L));
        journal.record(dispatched(coord, "lc4j-agent", "fetch",
                Map.of("url", "doc1")));
        journal.record(completed(coord, "lc4j-agent", "fetch",
                "doc1 content: StreamingChatModel supports tool bindings", 95L));
        journal.record(dispatched(coord, "lc4j-agent", "summarize",
                Map.of("text", "doc1 content")));
        journal.record(completed(coord, "lc4j-agent", "summarize",
                "LangChain4j provides Flux<ChatResponse> via StreamingChatModel.",
                210L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        assertEquals(3, snapshots.size(),
                "one snapshot per tool completion");
        // Verify the parent chain.
        assertTrue(snapshots.get(0).isRoot());
        assertEquals(snapshots.get(0).id(), snapshots.get(1).parent().orElseThrow());
        assertEquals(snapshots.get(1).id(), snapshots.get(2).parent().orElseThrow());
    }

    @Test
    @DisplayName("failure event is captured as a snapshot on the chain")
    void agentFailedIsAlsoCheckpointed() {
        var coord = "lc4j-coord-2";
        journal.record(dispatched(coord, "lc4j-agent", "search", Map.of()));
        journal.record(failed(coord, "lc4j-agent", "search",
                "rate limit exceeded on StreamingChatModel", 30L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        assertEquals(1, snapshots.size(), "Failed is an agent boundary");
        assertTrue(snapshots.get(0).state() instanceof CoordinationEvent.AgentFailed);
    }

    @Test
    @DisplayName("retry after failure continues the chain with a new snapshot")
    void retryAfterFailureContinuesChain() {
        var coord = "lc4j-coord-3";
        journal.record(failed(coord, "lc4j-agent", "search", "transient", 20L));
        var failureSnap = journal.lastSnapshot(coord);
        assertNotNull(failureSnap);

        // Retry emits another completed event.
        journal.record(completed(coord, "lc4j-agent", "search", "docs found", 180L));

        var snapshots = store.list(CheckpointQuery.forCoordination(coord));
        assertEquals(2, snapshots.size());
        // Retry's parent is the failure snapshot — preserves the causal chain.
        assertEquals(failureSnap, snapshots.get(1).parent().orElseThrow());
    }
}
