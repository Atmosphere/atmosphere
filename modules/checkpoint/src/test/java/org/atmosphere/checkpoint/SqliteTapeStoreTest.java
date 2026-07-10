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
package org.atmosphere.checkpoint;

import org.atmosphere.ai.tape.TapeQuery;
import org.atmosphere.ai.tape.TapeRun;
import org.atmosphere.ai.tape.TapeStatus;
import org.atmosphere.ai.tape.TapeStep;
import org.atmosphere.ai.tape.TapeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the crash-durable {@link SqliteTapeStore}: durable round-trip across a
 * reopen, seq ordering and the readSteps cursor, replay-equality, the
 * idempotent begin upsert with {@code MAX(seq)+1} continuation, write-once
 * terminal, append-after-terminal ignore, terminal-only retention, step-cap
 * truncation, append batch atomicity, and fork provenance.
 */
class SqliteTapeStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void durableRoundTripAcrossReopenPreservesEveryField() {
        var path = tempDir.resolve("tape.db");
        try (var store = new SqliteTapeStore(path)) {
            store.begin(new TapeRun("r1", "tape-a", "sess-1", "res-1", "alice", "/chat",
                    "model-1", "rt-1", 1234, TapeStatus.OPEN, null, 0, 0, false, null));
            store.append("r1", List.of(
                    new TapeStep("r1", 0, "text", "{\"v\":1,\"text\":\"hello\"}", 2000)));
            store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(1, 2, false));
        }
        try (var reopened = new SqliteTapeStore(path)) {
            assertTrue(reopened.durable());
            var run = findRun(reopened, "r1");
            assertEquals("tape-a", run.tapeId());
            assertEquals("sess-1", run.sessionId());
            assertEquals("res-1", run.resourceUuid());
            assertEquals("alice", run.userId());
            assertEquals("/chat", run.endpoint());
            assertEquals("model-1", run.model());
            assertEquals("rt-1", run.runtimeName());
            assertEquals(1234L, run.startedAt());
            assertEquals(TapeStatus.COMPLETED, run.status());
            assertNotNull(run.endedAt(), "the terminal must survive the reopen");
            assertEquals(1, run.stepCount());
            assertEquals(2, run.droppedSteps(), "the writer's counters must survive the reopen");
            assertFalse(run.truncated());
            assertNull(run.parentRunId());

            var steps = reopened.readSteps("r1", 0, 0);
            assertEquals(1, steps.size(), "recorded steps survive a reopen");
            var step = steps.get(0);
            assertEquals("r1", step.runId());
            assertEquals(0L, step.seq());
            assertEquals("text", step.kind());
            assertEquals("{\"v\":1,\"text\":\"hello\"}", step.payload());
            assertEquals(2000L, step.ts());
        }
    }

    @Test
    void stepsAreReturnedInSeqOrderAndTheCursorWindows() {
        try (var store = new SqliteTapeStore(tempDir.resolve("a.db"))) {
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
    }

    @Test
    void readingTwiceYieldsIdenticalResults() {
        try (var store = new SqliteTapeStore(tempDir.resolve("b.db"))) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", LongStream.range(0, 5).mapToObj(i -> step("r1", i)).toList());
            store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(5, 0, false));

            assertEquals(store.readSteps("r1", 0, 0), store.readSteps("r1", 0, 0),
                    "replay-equality: reading the tape twice must yield identical steps");
            assertEquals(store.listRuns(TapeQuery.all(0)), store.listRuns(TapeQuery.all(0)),
                    "replay-equality: listing runs twice must yield identical rows");
        }
    }

    @Test
    void reopenedRunContinuesSeqAtMaxPlusOne() {
        var path = tempDir.resolve("resume.db");
        try (var store = new SqliteTapeStore(path)) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", List.of(step("r1", 0), step("r1", 1), step("r1", 2)));
        }
        try (var reopened = new SqliteTapeStore(path)) {
            // Crash-resume re-drive: begin is an idempotent upsert that
            // refreshes identity but never resets status, steps, or counters.
            reopened.begin(new TapeRun("r1", "tape-a", "sess-2", "res-2", "alice", "/chat",
                    "model-2", "rt-2", 1000, TapeStatus.OPEN, null, 0, 0, false, null));
            var run = findRun(reopened, "r1");
            assertEquals(TapeStatus.OPEN, run.status(), "re-begin must not reset the status");
            assertEquals(3, run.stepCount(), "re-begin must not reset the run's steps");
            assertEquals("alice", run.userId(), "re-begin must refresh identity metadata");
            assertEquals("model-2", run.model());

            // The resumed writer restarts its numbering at 0; the store
            // continues the persisted history at MAX(seq)+1.
            reopened.append("r1", List.of(step("r1", 0), step("r1", 1)));
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L),
                    reopened.readSteps("r1", 0, 0).stream().map(TapeStep::seq).toList(),
                    "resumed appends must land after the existing steps, never collide");
        }
    }

    @Test
    void terminalStatusIsWriteOnce() {
        try (var store = new SqliteTapeStore(tempDir.resolve("c.db"))) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.markTerminal("r1", TapeStatus.CANCELLED, TapeStore.Counters.NONE);
            store.markTerminal("r1", TapeStatus.ERROR, new TapeStore.Counters(0, 5, true));

            var run = findRun(store, "r1");
            assertEquals(TapeStatus.CANCELLED, run.status(), "first terminal wins — never a flip");
            assertEquals(0, run.droppedSteps(), "the losing terminal's counters must be ignored");
            assertFalse(run.truncated());
            assertNotNull(run.endedAt(), "a terminal run must carry endedAt");

            assertThrows(IllegalArgumentException.class,
                    () -> store.markTerminal("r1", TapeStatus.OPEN, TapeStore.Counters.NONE),
                    "OPEN is not a terminal status");
            assertDoesNotThrow(
                    () -> store.markTerminal("nope", TapeStatus.ERROR, TapeStore.Counters.NONE),
                    "a terminal for an unknown run is ignored, not an error");
        }
    }

    @Test
    void firstTerminalCarriesTheWritersCounters() {
        try (var store = new SqliteTapeStore(tempDir.resolve("d.db"))) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", List.of(step("r1", 0), step("r1", 1)));
            store.markTerminal("r1", TapeStatus.ERROR, new TapeStore.Counters(2, 7, true));

            var run = findRun(store, "r1");
            assertEquals(TapeStatus.ERROR, run.status());
            assertEquals(7, run.droppedSteps());
            assertTrue(run.truncated(), "the writer's truncated flag must be OR-ed in");
            assertEquals(2, run.stepCount(), "stepCount reflects the store's own rows");
        }
    }

    @Test
    void appendAfterTerminalIsIgnoredNeverInserted() {
        try (var store = new SqliteTapeStore(tempDir.resolve("e.db"))) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", List.of(step("r1", 0), step("r1", 1)));
            store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(2, 0, false));
            store.append("r1", List.of(step("r1", 2), step("r1", 3)));

            assertEquals(2, store.readSteps("r1", 0, 0).size(),
                    "reject-or-ignore: steps must never land after the terminal");
            assertEquals(2, findRun(store, "r1").stepCount());
        }
    }

    @Test
    void appendForUnknownRunIsIgnored() {
        try (var store = new SqliteTapeStore(tempDir.resolve("f.db"))) {
            assertDoesNotThrow(() -> store.append("ghost", List.of(step("ghost", 0))));
            assertEquals(0, store.runCount(), "an append must never invent a run row");
            assertEquals(List.of(), store.readSteps("ghost", 0, 0));
        }
    }

    @Test
    void retentionEvictsOldestTerminalButNeverOpen() {
        try (var store = new SqliteTapeStore(tempDir.resolve("g.db"), 2, 100)) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", List.of(step("r1", 0)));
            store.begin(openRun("r2", "tape-a", 2000));
            store.begin(openRun("r3", "tape-a", 3000));
            assertEquals(3, store.runCount(),
                    "over-capacity with only OPEN runs must not evict anything");

            store.markTerminal("r1", TapeStatus.COMPLETED, TapeStore.Counters.NONE);
            assertEquals(2, store.runCount(), "the oldest terminal run must be evicted");
            assertTrue(store.listRuns(TapeQuery.all(0)).stream()
                            .noneMatch(r -> r.runId().equals("r1")),
                    "r1 was terminal and oldest — it must be the one evicted");
            assertEquals(List.of(), store.readSteps("r1", 0, 0),
                    "an evicted run's steps must be deleted with it");

            store.begin(openRun("r4", "tape-a", 4000));
            assertEquals(3, store.runCount(),
                    "r2/r3/r4 are all OPEN — over-capacity again, still no eviction");
        }
    }

    @Test
    void stepCapStopsRecordingAndFlagsTruncatedNeverThrows() {
        try (var store = new SqliteTapeStore(tempDir.resolve("h.db"), 10, 3)) {
            store.begin(openRun("r1", "tape-a", 1000));
            assertDoesNotThrow(() -> store.append("r1",
                    LongStream.range(0, 5).mapToObj(i -> step("r1", i)).toList()));

            assertEquals(3, store.readSteps("r1", 0, 0).size(),
                    "appends beyond maxStepsPerRun must be ignored");
            var run = findRun(store, "r1");
            assertTrue(run.truncated(), "the run must be flagged truncated, not failed");
            assertEquals(3, run.stepCount());

            assertDoesNotThrow(() -> store.append("r1", List.of(step("r1", 5))),
                    "appends past the cap keep being ignored without a throw");
            assertEquals(3, store.readSteps("r1", 0, 0).size());
        }
    }

    @Test
    void appendBatchIsAtomicRollingBackOnFailure() {
        try (var store = new SqliteTapeStore(tempDir.resolve("i.db"))) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", List.of(step("r1", 0), step("r1", 1)));

            // seq 1 collides with an existing row mid-batch: the whole batch
            // must roll back, leaving no partial rows and a consistent count.
            assertThrows(IllegalStateException.class, () -> store.append("r1",
                    List.of(step("r1", 2), step("r1", 1), step("r1", 3))));
            assertEquals(List.of(0L, 1L),
                    store.readSteps("r1", 0, 0).stream().map(TapeStep::seq).toList(),
                    "the failed batch must leave no partial rows");
            assertEquals(2, findRun(store, "r1").stepCount(),
                    "step_count must stay transactionally consistent with the rows");

            // Autocommit is restored: the store keeps working after the failure.
            store.append("r1", List.of(step("r1", 2)));
            assertEquals(3, store.readSteps("r1", 0, 0).size());
        }
    }

    @Test
    void forkCopiesStepsAndRecordsParentProvenance() {
        try (var store = new SqliteTapeStore(tempDir.resolve("j.db"))) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", List.of(step("r1", 0), step("r1", 1), step("r1", 2)));
            store.markTerminal("r1", TapeStatus.COMPLETED, new TapeStore.Counters(3, 0, false));

            var forkId = store.fork("r1").orElseThrow();
            assertNotEquals("r1", forkId);
            var fork = findRun(store, forkId);
            assertEquals("r1", fork.parentRunId(), "fork provenance must point at the source");
            assertEquals(TapeStatus.OPEN, fork.status(), "a fork is a fresh branch");
            assertNull(fork.endedAt());
            assertEquals(3, fork.stepCount());

            var forkSteps = store.readSteps(forkId, 0, 0);
            assertEquals(3, forkSteps.size(), "the source's steps must be copied");
            for (int i = 0; i < forkSteps.size(); i++) {
                assertEquals(forkId, forkSteps.get(i).runId(),
                        "copied steps must be re-keyed under the fork's run id");
                assertEquals(i, forkSteps.get(i).seq());
                assertEquals(step("r1", i).payload(), forkSteps.get(i).payload());
            }

            // A writer appending to the fork restarts at 0; the store places
            // its steps after the copied history.
            store.append(forkId, List.of(step(forkId, 0)));
            assertEquals(List.of(0L, 1L, 2L, 3L),
                    store.readSteps(forkId, 0, 0).stream().map(TapeStep::seq).toList());

            assertTrue(store.fork("unknown").isEmpty(), "forking an unknown run yields empty");
        }
    }

    @Test
    void listRunsFiltersByTapeIdAndStatusNewestFirstWithLimit() {
        try (var store = new SqliteTapeStore(tempDir.resolve("k.db"))) {
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
    }

    @Test
    void removeRunDeletesTheRunAndItsSteps() {
        try (var store = new SqliteTapeStore(tempDir.resolve("l.db"))) {
            store.begin(openRun("r1", "tape-a", 1000));
            store.append("r1", List.of(step("r1", 0), step("r1", 1)));
            store.removeRun("r1");

            assertEquals(0, store.runCount());
            assertEquals(List.of(), store.readSteps("r1", 0, 0));
        }
    }

    @Test
    void constructorRejectsNonPositiveBoundsAndReportsRuntimeTruth() {
        var path = tempDir.resolve("m.db");
        assertThrows(IllegalArgumentException.class, () -> new SqliteTapeStore(path, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SqliteTapeStore(path, 10, 0));
        try (var store = new SqliteTapeStore(path)) {
            assertTrue(store.durable(), "SQLite tape store is crash-durable");
            assertEquals("sqlite", store.name());
            assertEquals(SqliteTapeStore.DEFAULT_MAX_RUNS, store.maxRuns());
            assertEquals(SqliteTapeStore.DEFAULT_MAX_STEPS_PER_RUN, store.maxStepsPerRun());
        }
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

    private static TapeRun findRun(SqliteTapeStore store, String runId) {
        return store.listRuns(TapeQuery.all(0)).stream()
                .filter(r -> r.runId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("run " + runId + " not in store"));
    }
}
