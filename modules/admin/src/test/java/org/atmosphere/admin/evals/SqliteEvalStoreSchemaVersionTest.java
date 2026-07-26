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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-version stamping for the SQLite eval stores. The run store and the
 * dataset store routinely share one database file, so each keys its version row
 * by its own table and the two version independently.
 */
class SqliteEvalStoreSchemaVersionTest {

    @TempDir
    Path tempDir;

    private static EvalRun run(String id) {
        return new EvalRun(id, "base-a", Instant.parse("2026-07-01T10:00:00Z"), "v1",
                "prompt", "response", true, Map.of("relevance", 0.9), "judge-model", true, "ok");
    }

    private static EvalCase evalCase(String id) {
        return new EvalCase(id, "What is the capital of France?", "Paris", "journal:c-9",
                List.of("geo"), Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void freshStoresStampTheirInitialSchema() throws SQLException {
        var runsDb = tempDir.resolve("runs.db");
        try (var store = new SqliteEvalRunStore(runsDb)) {
            store.save(run("r1"));
        }
        assertVersion(runsDb, "eval_runs", 1);

        var datasetDb = tempDir.resolve("dataset.db");
        try (var store = new SqliteEvalDatasetStore(datasetDb)) {
            store.save(evalCase("case-1"));
        }
        assertVersion(datasetDb, "eval_dataset", 1);
    }

    @Test
    void storesSharingOneFileVersionIndependently() throws SQLException {
        var db = tempDir.resolve("shared-evals.db");
        try (var runs = new SqliteEvalRunStore(db);
             var dataset = new SqliteEvalDatasetStore(db)) {
            runs.save(run("r1"));
            dataset.save(evalCase("case-1"));
        }

        assertVersion(db, "eval_runs", 1);
        assertVersion(db, "eval_dataset", 1);
    }

    @Test
    void legacyDatabaseIsAdoptedAndKeepsItsRows() throws SQLException {
        var db = tempDir.resolve("legacy-runs.db");
        // A database written before version stamping existed.
        try (var conn = open(db);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE eval_runs (
                    id TEXT PRIMARY KEY,
                    baseline TEXT NOT NULL,
                    ts_iso TEXT NOT NULL,
                    ts_millis INTEGER NOT NULL,
                    agent_version TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    judge_response TEXT NOT NULL,
                    verdict INTEGER,
                    scores TEXT NOT NULL,
                    judge_model TEXT NOT NULL,
                    passed INTEGER NOT NULL,
                    notes TEXT NOT NULL
                )""");
            stmt.execute("INSERT INTO eval_runs VALUES ('legacy-1', 'base-a',"
                    + " '2026-01-01T00:00:00Z', 1767225600000, 'v0', 'legacy prompt',"
                    + " 'legacy response', 1, '', 'judge-model', 1, 'kept')");
        }

        try (var store = new SqliteEvalRunStore(db)) {
            var loaded = store.findById("legacy-1").orElseThrow();
            assertEquals("legacy prompt", loaded.prompt());
            assertEquals("kept", loaded.notes());
            store.save(run("r-new"));
            assertEquals(2, store.list().size());
        }

        assertVersion(db, "eval_runs", 1);
    }

    @Test
    void versionNewerThanTheCodeRefusesToOpen() throws SQLException {
        var db = tempDir.resolve("future-runs.db");
        new SqliteEvalRunStore(db).close();

        try (var conn = open(db);
             var stmt = conn.createStatement()) {
            stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                    + " SET version = 6 WHERE component = 'eval_runs'");
        }

        var failure = assertThrows(IllegalStateException.class, () -> new SqliteEvalRunStore(db));
        assertTrue(failure.getMessage().contains("future-runs.db"), failure.getMessage());
        assertTrue(failure.getMessage().contains("version 6"), failure.getMessage());
        assertTrue(failure.getMessage().contains("up to 1"), failure.getMessage());
    }

    @Test
    void reopeningAStampedDatabaseIsANoOp() throws SQLException {
        var db = tempDir.resolve("reopen-dataset.db");
        try (var store = new SqliteEvalDatasetStore(db)) {
            store.save(evalCase("case-1"));
        }
        try (var reopened = new SqliteEvalDatasetStore(db)) {
            assertEquals(1, reopened.list().size());
            reopened.save(evalCase("case-2"));
            assertEquals(2, reopened.list().size());
        }
        assertVersion(db, "eval_dataset", 1);
    }

    private static void assertVersion(Path dbPath, String component, int expected) throws SQLException {
        try (var conn = open(dbPath)) {
            assertEquals(expected, SchemaMigrations.readVersion(conn, component),
                    component + " version");
        }
    }

    private static Connection open(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }
}
