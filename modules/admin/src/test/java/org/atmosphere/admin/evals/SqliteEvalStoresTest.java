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
package org.atmosphere.admin.evals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteEvalStoresTest {

    @TempDir
    Path tempDir;

    private static EvalRun run(String id, String baseline, Instant ts, Boolean verdict,
                               Map<String, Double> scores, String notes) {
        return new EvalRun(id, baseline, ts, "v1", "prompt of " + id, "response of " + id,
                verdict, scores, "judge-model", Boolean.TRUE.equals(verdict), notes);
    }

    @Test
    void runStoreRoundTripsAcrossReopen() {
        var db = tempDir.resolve("evals.db");
        var older = Instant.parse("2026-07-01T10:00:00Z");
        var newer = Instant.parse("2026-07-02T10:00:00.123Z");
        var withNullVerdict = run("r1", "base-a", older, null, Map.of(), "notes with = & \n chars");
        var withScores = run("r2", "base-a", newer, true,
                Map.of("relevance", 0.9, "safety", 1.0), "ok");

        try (var store = new SqliteEvalRunStore(db)) {
            store.save(withNullVerdict);
            store.save(withScores);
        }

        try (var reopened = new SqliteEvalRunStore(db)) {
            var listed = reopened.list();
            assertEquals(2, listed.size());
            assertEquals("r2", listed.getFirst().id(), "most recent first");
            assertEquals(withScores, listed.getFirst(), "records must round-trip field-for-field");
            assertEquals(withNullVerdict, reopened.findById("r1").orElseThrow());
            assertNull(reopened.findById("r1").orElseThrow().verdict());
            assertEquals(List.of(withScores, withNullVerdict), reopened.listForBaseline("base-a"));
            assertTrue(reopened.listForBaseline("other").isEmpty());
        }
    }

    @Test
    void datasetStoreRoundTripsAcrossReopen() {
        var db = tempDir.resolve("dataset.db");
        var evalCase = new EvalCase("case-1", "What is the capital of France?", "Paris",
                "journal:c-9", List.of("naïve tag", "x=y&z"), Instant.parse("2026-07-01T00:00:00Z"));

        try (var store = new SqliteEvalDatasetStore(db)) {
            store.save(evalCase);
        }

        try (var reopened = new SqliteEvalDatasetStore(db)) {
            assertEquals(evalCase, reopened.findById("case-1").orElseThrow(),
                    "cases (including tags with reserved characters) must round-trip");
            assertEquals(1, reopened.list().size());
        }
    }

    @Test
    void duplicateIdsAreRejectedByBothStores() {
        try (var runStore = new SqliteEvalRunStore(tempDir.resolve("dup.db"));
             var datasetStore = new SqliteEvalDatasetStore(tempDir.resolve("dup.db"))) {
            runStore.save(run("r1", "base", Instant.now(), true, Map.of(), ""));
            assertThrows(IllegalStateException.class,
                    () -> runStore.save(run("r1", "base", Instant.now(), true, Map.of(), "")));

            var evalCase = new EvalCase("c1", "p", "", "manual", List.of(), Instant.now());
            datasetStore.save(evalCase);
            assertThrows(IllegalStateException.class, () -> datasetStore.save(evalCase));
        }
    }

    @Test
    void runStoreEvictsOldestPerBaselineBeyondCap() {
        try (var store = new SqliteEvalRunStore(tempDir.resolve("cap.db"), 2)) {
            store.save(run("old", "base", Instant.parse("2026-01-01T00:00:00Z"), true, Map.of(), ""));
            store.save(run("mid", "base", Instant.parse("2026-01-02T00:00:00Z"), true, Map.of(), ""));
            store.save(run("other", "different", Instant.parse("2026-01-01T00:00:00Z"),
                    true, Map.of(), ""));
            store.save(run("new", "base", Instant.parse("2026-01-03T00:00:00Z"), true, Map.of(), ""));

            assertTrue(store.findById("old").isEmpty(), "oldest row past the cap must be evicted");
            assertEquals(2, store.listForBaseline("base").size());
            assertTrue(store.findById("other").isPresent(),
                    "eviction is per baseline — other baselines are untouched");
        }
    }

    @Test
    void datasetStoreEvictsOldestBeyondCap() {
        try (var store = new SqliteEvalDatasetStore(tempDir.resolve("dcap.db"), 2)) {
            store.save(new EvalCase("old", "p", "", "manual", List.of(),
                    Instant.parse("2026-01-01T00:00:00Z")));
            store.save(new EvalCase("mid", "p", "", "manual", List.of(),
                    Instant.parse("2026-01-02T00:00:00Z")));
            store.save(new EvalCase("new", "p", "", "manual", List.of(),
                    Instant.parse("2026-01-03T00:00:00Z")));

            assertTrue(store.findById("old").isEmpty());
            assertEquals(2, store.list().size());
        }
    }

    @Test
    void deleteRemovesRows() {
        try (var runStore = new SqliteEvalRunStore(tempDir.resolve("del.db"));
             var datasetStore = new SqliteEvalDatasetStore(tempDir.resolve("del.db"))) {
            runStore.save(run("r1", "base", Instant.now(), true, Map.of(), ""));
            runStore.delete("r1");
            assertTrue(runStore.findById("r1").isEmpty());

            datasetStore.save(new EvalCase("c1", "p", "", "manual", List.of(), Instant.now()));
            datasetStore.delete("c1");
            assertTrue(datasetStore.findById("c1").isEmpty());
        }
    }
}
