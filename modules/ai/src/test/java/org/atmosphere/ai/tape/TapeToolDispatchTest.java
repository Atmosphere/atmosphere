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
package org.atmosphere.ai.tape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link TapeRecorder#recordCompletedRun} tapes a non-streaming dispatch (an
 * A2A tool-agent skill that bypasses the pipeline) as one terminal run so
 * tool-backed coordination children still form a replay tree. Verifies the run
 * links to its coordinator via {@code parentRunId} and that {@link TapeReplay}
 * reconstructs its input/output.
 */
class TapeToolDispatchTest {

    @Test
    void recordsAToolDispatchLinkedToItsCoordinator() {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        try {
            recorder.recordCompletedRun("task-1", "web_search", "ceo-run-1", "user-9",
                    "assess the market", "the market is large", TapeStatus.COMPLETED);

            var runs = store.listRuns(new TapeQuery(null, null, 10));
            assertEquals(1, runs.size(), "the dispatch is taped as one run");
            var run = runs.get(0);
            assertEquals("task-1", run.tapeId());
            assertEquals("ceo-run-1", run.parentRunId(), "child links to the coordinator run");
            assertEquals(TapeStatus.COMPLETED, run.status());
            assertEquals("a2a-skill", run.runtimeName());

            var replayed = TapeReplay.reconstruct(store, run.runId()).orElseThrow();
            assertEquals(List.of(new TapeReplay.ReplayedRun.Message("user", "assess the market")),
                    replayed.input());
            assertEquals("the market is large", replayed.output());
        } finally {
            recorder.close();
        }
    }

    @Test
    void toolDispatchesAppearAsChildrenInTheReplayTree() {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        try {
            // Coordinator run (as the endpoint/streaming path would record it).
            store.begin(new TapeRun("ceo-1", "conv", "sess", "res", "user-9", "/agent/ceo",
                    "gpt", "built-in", 1000, TapeStatus.OPEN, null, 0, 0, false, null));
            store.markTerminal("ceo-1", TapeStatus.COMPLETED, new TapeStore.Counters(0, 0, false));
            // Two tool-agent children dispatched under it.
            recorder.recordCompletedRun("task-r", "research", "ceo-1", "user-9",
                    "research gardening", "found 3 sources", TapeStatus.COMPLETED);
            recorder.recordCompletedRun("task-f", "finance", "ceo-1", "user-9",
                    "model burn", "runway 18mo", TapeStatus.COMPLETED);

            var tree = TapeReplay.reconstructTree(store, "ceo-1").orElseThrow();
            assertEquals(3, tree.runCount(), "coordinator + 2 tool-agent children");
            assertTrue(tree.children().stream().allMatch(c -> "ceo-1".equals(c.parentRunId())));
            assertEquals(java.util.Set.of("research", "finance"),
                    tree.children().stream().map(TapeReplay.ReplayedRun::endpoint)
                            .collect(java.util.stream.Collectors.toSet()));
        } finally {
            recorder.close();
        }
    }
}
