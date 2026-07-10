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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryTapeStore} contract: bounded retention with terminal-only
 * eviction, step ordering, the readSteps cursor, fork provenance,
 * append-after-terminal ignore, write-once terminal, the per-run step cap,
 * and the idempotent begin upsert.
 */
class InMemoryTapeStoreTest {

    @Test
    void evictsOldestTerminalRunOnlyNeverAnOpenOne() {
        var store = new InMemoryTapeStore(2, 10);
        store.begin(openRun("r1", "tape-a", 1000));
        store.begin(openRun("r2", "tape-a", 2000));
        store.begin(openRun("r3", "tape-a", 3000));
        assertEquals(3, store.runCount(),
                "over-capacity with only OPEN runs must not evict anything");

        store.markTerminal("r1", TapeStatus.COMPLETED, TapeStore.Counters.NONE);
        assertEquals(2, store.runCount(), "the oldest terminal run must be evicted");
        assertTrue(store.listRuns(TapeQuery.all(0)).stream()
                        .noneMatch(r -> r.runId().equals("r1")),
                "r1 was terminal and oldest — it must be the one evicted");

        store.begin(openRun("r4", "tape-a", 4000));
        assertEquals(3, store.runCount(),
                "r2/r3/r4 are all OPEN — over-capacity again, still no eviction");
    }

    @Test
    void stepsAreReturnedInSeqOrderAndTheCursorWindows() {
        var store = new InMemoryTapeStore();
        store.begin(openRun("r1", "tape-a", 1000));
        store.append("r1", LongStream.range(0, 10).mapToObj(i -> step("r1", i)).toList());

        var all = store.readSteps("r1", 0, 0);
        assertEquals(10, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i, all.get(i).seq(), "ascending seq order expected");
        }

        var window = store.readSteps("r1", 5, 3);
        assertEquals(List.of(5L, 6L, 7L),
                window.stream().map(TapeStep::seq).toList(),
                "the cursor must start at fromSeq and honor max");
        assertEquals(List.of(), store.readSteps("unknown", 0, 0));
    }

    @Test
    void appendAfterTerminalIsIgnored() {
        var store = new InMemoryTapeStore();
        store.begin(openRun("r1", "tape-a", 1000));
        store.append("r1", List.of(step("r1", 0), step("r1", 1)));
        store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(2, 0, false));
        store.append("r1", List.of(step("r1", 2), step("r1", 3)));

        assertEquals(2, store.readSteps("r1", 0, 0).size(),
                "reject-or-ignore: steps must never land after the terminal");
        assertEquals(2, findRun(store, "r1").stepCount());
    }

    @Test
    void terminalStatusIsWriteOnce() {
        var store = new InMemoryTapeStore();
        store.begin(openRun("r1", "tape-a", 1000));
        store.markTerminal("r1", TapeStatus.CANCELLED, TapeStore.Counters.NONE);
        store.markTerminal("r1", TapeStatus.ERROR, new TapeStore.Counters(0, 5, true));

        var run = findRun(store, "r1");
        assertEquals(TapeStatus.CANCELLED, run.status(), "first terminal wins — never a flip");
        assertEquals(0, run.droppedSteps(), "the losing terminal's counters must be ignored");
        assertNotNull(run.endedAt(), "a terminal run must carry endedAt");

        assertThrows(IllegalArgumentException.class,
                () -> store.markTerminal("r1", TapeStatus.OPEN, TapeStore.Counters.NONE),
                "OPEN is not a terminal status");
    }

    @Test
    void forkCopiesStepsAndRecordsParentProvenance() {
        var store = new InMemoryTapeStore();
        store.begin(openRun("r1", "tape-a", 1000));
        store.append("r1", List.of(step("r1", 0), step("r1", 1), step("r1", 2)));
        store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(3, 0, false));

        var forkId = store.fork("r1").orElseThrow();
        assertNotEquals("r1", forkId);
        var fork = findRun(store, forkId);
        assertEquals("r1", fork.parentRunId(), "fork provenance must point at the source");
        assertEquals(TapeStatus.OPEN, fork.status(), "a fork is a fresh branch");
        assertNull(fork.endedAt());

        var forkSteps = store.readSteps(forkId, 0, 0);
        assertEquals(3, forkSteps.size(), "the source's steps must be copied");
        for (int i = 0; i < forkSteps.size(); i++) {
            assertEquals(forkId, forkSteps.get(i).runId(),
                    "copied steps must be re-keyed under the fork's run id");
            assertEquals(i, forkSteps.get(i).seq());
            assertEquals(step("r1", i).payload(), forkSteps.get(i).payload());
        }

        assertTrue(store.fork("unknown").isEmpty(), "forking an unknown run yields empty");
    }

    @Test
    void stepCapStopsRecordingAndFlagsTruncated() {
        var store = new InMemoryTapeStore(10, 3);
        store.begin(openRun("r1", "tape-a", 1000));
        store.append("r1", LongStream.range(0, 5).mapToObj(i -> step("r1", i)).toList());

        assertEquals(3, store.readSteps("r1", 0, 0).size(),
                "appends beyond maxStepsPerRun must be ignored");
        assertTrue(findRun(store, "r1").truncated(),
                "the run must be flagged truncated, not failed");
    }

    @Test
    void beginIsAnIdempotentUpsertThatNeverRegressesState() {
        var store = new InMemoryTapeStore();
        store.begin(openRun("r1", "tape-a", 1000));
        store.append("r1", List.of(step("r1", 0), step("r1", 1)));

        // Re-begin (the crash-resume path): identity may refresh, steps stay.
        store.begin(new TapeRun("r1", "tape-a", "sess-2", "res-2", "alice", "/chat",
                "model-2", "rt-2", 1000, TapeStatus.OPEN, null, 0, 0, false, null));
        var run = findRun(store, "r1");
        assertEquals(2, run.stepCount(), "re-begin must not reset the run's steps");
        assertEquals("alice", run.userId(), "re-begin must refresh identity metadata");
        assertEquals("model-2", run.model());
        assertEquals(TapeStatus.OPEN, run.status());
        assertEquals(1, store.runCount());
    }

    @Test
    void listRunsFiltersByTapeIdAndStatusNewestFirstWithLimit() {
        var store = new InMemoryTapeStore();
        store.begin(openRun("r1", "tape-a", 1000));
        store.begin(openRun("r2", "tape-b", 2000));
        store.begin(openRun("r3", "tape-a", 3000));
        store.markTerminal("r3", TapeStatus.ERROR, TapeStore.Counters.NONE);

        assertEquals(List.of("r3", "r1"),
                store.listRuns(TapeQuery.byTapeId("tape-a", 0)).stream()
                        .map(TapeRun::runId).toList(),
                "tapeId filter, newest-first ordering");
        assertEquals(List.of("r3"),
                store.listRuns(TapeQuery.byStatus(TapeStatus.ERROR, 0)).stream()
                        .map(TapeRun::runId).toList());
        assertEquals(List.of("r3", "r2"),
                store.listRuns(TapeQuery.all(2)).stream().map(TapeRun::runId).toList(),
                "limit must cap the newest-first result");
    }

    @Test
    void constructorRejectsNonPositiveBounds() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTapeStore(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTapeStore(10, 0));
        assertEquals(InMemoryTapeStore.DEFAULT_MAX_RUNS, new InMemoryTapeStore().maxRuns());
        assertEquals(InMemoryTapeStore.DEFAULT_MAX_STEPS_PER_RUN,
                new InMemoryTapeStore().maxStepsPerRun());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static TapeRun openRun(String runId, String tapeId, long startedAt) {
        return new TapeRun(runId, tapeId, "sess-1", "res-1", null, "/chat", "model-1",
                "rt", startedAt, TapeStatus.OPEN, null, 0, 0, false, null);
    }

    private static TapeStep step(String runId, long seq) {
        return new TapeStep(runId, seq, "progress", "{\"v\":1,\"message\":\"p" + seq + "\"}",
                1000 + seq);
    }

    private static TapeRun findRun(InMemoryTapeStore store, String runId) {
        return store.listRuns(TapeQuery.all(0)).stream()
                .filter(r -> r.runId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("run " + runId + " not in store"));
    }
}
