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

import org.atmosphere.ai.resume.EffectKind;
import org.atmosphere.ai.resume.RunJournal.RunRecord;
import org.atmosphere.ai.tape.TapeRun;
import org.atmosphere.ai.tape.TapeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for {@link SchemaMigrations}: fresh stamp, legacy adoption, ordered
 * idempotent steps, no-op reopen, and the fail-closed refusal when the on-disk
 * schema is newer than the code understands.
 *
 * <p>Exercised both directly (counting steps) and through
 * {@link SqliteCheckpointStore}, whose historical {@code state_type} column
 * probe is now migration step 1 to 2.</p>
 */
class SchemaMigrationsTest {

    @TempDir
    Path tempDir;

    // --- the helper itself ---------------------------------------------------

    @Test
    void freshDatabaseRunsEveryStepAndStampsTheFinalVersion() throws SQLException {
        var applied = new AtomicInteger();
        try (var conn = open("fresh.db")) {
            SchemaMigrations.migrate(conn, "widget", List.of(
                    c -> {
                        applied.incrementAndGet();
                        try (var stmt = c.createStatement()) {
                            stmt.execute("CREATE TABLE IF NOT EXISTS widget (id TEXT PRIMARY KEY)");
                        }
                    },
                    c -> {
                        applied.incrementAndGet();
                        try (var stmt = c.createStatement()) {
                            stmt.execute("ALTER TABLE widget ADD COLUMN label TEXT");
                        }
                    }));

            assertEquals(2, applied.get(), "both steps must run on a fresh database");
            assertEquals(2, SchemaMigrations.readVersion(conn, "widget"));
            assertTrue(hasColumn(conn, "widget", "label"));
        }
    }

    @Test
    void reopeningAnUpToDateDatabaseRunsNoStep() throws SQLException {
        var applied = new AtomicInteger();
        List<SchemaMigrations.MigrationStep> steps = List.of(c -> {
            applied.incrementAndGet();
            try (var stmt = c.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS widget (id TEXT PRIMARY KEY)");
            }
        });

        try (var conn = open("reopen.db")) {
            SchemaMigrations.migrate(conn, "widget", steps);
            assertEquals(1, applied.get());

            SchemaMigrations.migrate(conn, "widget", steps);
            SchemaMigrations.migrate(conn, "widget", steps);

            assertEquals(1, applied.get(), "an up-to-date database must not re-run steps");
            assertEquals(1, SchemaMigrations.readVersion(conn, "widget"));
        }
    }

    @Test
    void unstampedDatabaseWithExistingTableIsAdoptedAsVersionOne() throws SQLException {
        var applied = new AtomicInteger();
        try (var conn = open("legacy.db")) {
            // A database written before version stamping existed.
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE widget (id TEXT PRIMARY KEY)");
                stmt.execute("INSERT INTO widget (id) VALUES ('kept')");
            }

            SchemaMigrations.migrate(conn, "widget", List.of(
                    c -> applied.addAndGet(100),
                    c -> {
                        applied.incrementAndGet();
                        try (var stmt = c.createStatement()) {
                            stmt.execute("ALTER TABLE widget ADD COLUMN label TEXT");
                        }
                    }));

            assertEquals(1, applied.get(), "step 1 must be skipped — the legacy schema IS version 1");
            assertEquals(2, SchemaMigrations.readVersion(conn, "widget"));
            assertTrue(hasColumn(conn, "widget", "label"));
            assertEquals(1, countRows(conn, "widget"), "adoption must not touch existing data");
        }
    }

    @Test
    void adoptedDatabaseWithNoLaterStepsIsStampedSoTheProbeRunsOnce() throws SQLException {
        try (var conn = open("adopt-only.db")) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE widget (id TEXT PRIMARY KEY)");
            }
            SchemaMigrations.migrate(conn, "widget", List.of(c -> {
                throw new IllegalStateException("step 1 must not run for an adopted database");
            }));

            assertEquals(1, SchemaMigrations.readVersion(conn, "widget"));
        }
    }

    @Test
    void versionNewerThanTheCodeRefusesToOpen() throws SQLException {
        try (var conn = open("from-the-future.db")) {
            SchemaMigrations.migrate(conn, "widget", List.of(c -> {
                try (var stmt = c.createStatement()) {
                    stmt.execute("CREATE TABLE IF NOT EXISTS widget (id TEXT PRIMARY KEY)");
                }
            }));
            // A newer build stamped a schema this code cannot read.
            try (var stmt = conn.createStatement()) {
                stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                        + " SET version = 7 WHERE component = 'widget'");
            }

            var failure = assertThrows(IllegalStateException.class, () ->
                    SchemaMigrations.migrate(conn, "widget", List.of(c -> { })));

            var message = failure.getMessage();
            assertTrue(message.contains("from-the-future.db"), "must name the database: " + message);
            assertTrue(message.contains("version 7"), "must report the found version: " + message);
            assertTrue(message.contains("up to 1"), "must report the supported version: " + message);
        }
    }

    @Test
    void separateComponentsInOneDatabaseVersionIndependently() throws SQLException {
        try (var conn = open("shared.db")) {
            SchemaMigrations.migrate(conn, "alpha", List.of(c -> {
                try (var stmt = c.createStatement()) {
                    stmt.execute("CREATE TABLE IF NOT EXISTS alpha (id TEXT PRIMARY KEY)");
                }
            }));
            SchemaMigrations.migrate(conn, "beta", List.of(
                    c -> {
                        try (var stmt = c.createStatement()) {
                            stmt.execute("CREATE TABLE IF NOT EXISTS beta (id TEXT PRIMARY KEY)");
                        }
                    },
                    c -> {
                        try (var stmt = c.createStatement()) {
                            stmt.execute("ALTER TABLE beta ADD COLUMN label TEXT");
                        }
                    }));

            assertEquals(1, SchemaMigrations.readVersion(conn, "alpha"));
            assertEquals(2, SchemaMigrations.readVersion(conn, "beta"));
        }
    }

    @Test
    void aFailingStepRollsBackAndLeavesTheEarlierVersionStamped() throws SQLException {
        try (var conn = open("failing.db")) {
            assertThrows(SQLException.class, () -> SchemaMigrations.migrate(conn, "widget", List.of(
                    c -> {
                        try (var stmt = c.createStatement()) {
                            stmt.execute("CREATE TABLE IF NOT EXISTS widget (id TEXT PRIMARY KEY)");
                        }
                    },
                    c -> {
                        try (var stmt = c.createStatement()) {
                            stmt.execute("ALTER TABLE widget ADD COLUMN label TEXT");
                        }
                        throw new SQLException("migration 1 -> 2 blew up");
                    })));

            // Step 1 committed on its own; step 2 rolled back with its stamp.
            assertEquals(1, SchemaMigrations.readVersion(conn, "widget"));
            assertFalse(hasColumn(conn, "widget", "label"),
                    "the failed step's DDL must be rolled back with its version stamp");
            assertTrue(conn.getAutoCommit(), "the caller's auto-commit mode must be restored");
        }
    }

    // --- through a real store ------------------------------------------------

    @Test
    void freshCheckpointStoreStampsItsCurrentSchema() throws SQLException {
        var dbPath = tempDir.resolve("checkpoints.db");
        var store = new SqliteCheckpointStore(dbPath);
        store.start();
        try {
            store.save(WorkflowSnapshot.root("coord-1", "state"));
        } finally {
            store.stop();
        }

        try (var conn = openPath(dbPath)) {
            assertEquals(2, SchemaMigrations.readVersion(conn, "checkpoints"));
        }
    }

    @Test
    void legacyCheckpointDatabaseIsAdoptedMigratedAndKeepsItsRows() throws SQLException {
        var dbPath = tempDir.resolve("legacy-checkpoints.db");
        // The pre-state_type schema, written by an older build.
        try (var conn = openPath(dbPath);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE checkpoints (
                    id TEXT PRIMARY KEY,
                    parent_id TEXT,
                    coordination_id TEXT NOT NULL,
                    agent_name TEXT,
                    state_json TEXT,
                    metadata_json TEXT,
                    created_at TEXT NOT NULL
                )""");
            stmt.execute("INSERT INTO checkpoints "
                    + "(id, coordination_id, state_json, metadata_json, created_at) VALUES "
                    + "('legacy-1', 'coord-legacy', '\"carried over\"', '{}', '"
                    + Instant.parse("2026-01-01T00:00:00Z") + "')");
        }

        var store = new SqliteCheckpointStore(dbPath);
        store.start();
        try {
            var loaded = store.load(CheckpointId.of("legacy-1"));
            assertTrue(loaded.isPresent(), "the legacy row must survive adoption");
            assertEquals("carried over", loaded.get().state());
            assertEquals("coord-legacy", loaded.get().coordinationId());

            // The 1 -> 2 step ran: new writes can record their state type.
            store.save(WorkflowSnapshot.root("coord-new", "after migration"));
        } finally {
            store.stop();
        }

        try (var conn = openPath(dbPath)) {
            assertEquals(2, SchemaMigrations.readVersion(conn, "checkpoints"));
            assertTrue(hasColumn(conn, "checkpoints", "state_type"));
            assertEquals(2, countRows(conn, "checkpoints"));
        }
    }

    @Test
    void checkpointStoreRefusesADatabaseNewerThanTheCode() throws SQLException {
        var dbPath = tempDir.resolve("future-checkpoints.db");
        var store = new SqliteCheckpointStore(dbPath);
        store.start();
        store.stop();

        try (var conn = openPath(dbPath);
             var stmt = conn.createStatement()) {
            stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                    + " SET version = 99 WHERE component = 'checkpoints'");
        }

        var newer = new SqliteCheckpointStore(dbPath);
        var failure = assertThrows(IllegalStateException.class, newer::start);
        assertTrue(failure.getMessage().contains("future-checkpoints.db"), failure.getMessage());
        assertTrue(failure.getMessage().contains("version 99"), failure.getMessage());
        newer.stop();
    }

    @Test
    void reopeningAMigratedCheckpointStoreIsANoOp() throws SQLException {
        var dbPath = tempDir.resolve("reopen-checkpoints.db");
        var first = new SqliteCheckpointStore(dbPath);
        first.start();
        var saved = first.save(WorkflowSnapshot.root("coord-1", "kept"));
        first.stop();

        var second = new SqliteCheckpointStore(dbPath);
        second.start();
        try {
            assertTrue(second.load(saved.id()).isPresent());
        } finally {
            second.stop();
        }

        try (var conn = openPath(dbPath)) {
            assertEquals(2, SchemaMigrations.readVersion(conn, "checkpoints"));
            assertEquals(1, countRows(conn, "checkpoints"));
        }
    }

    @Test
    void everySqliteStoreInTheModuleStampsItsSchema() throws SQLException {
        var timerDb = tempDir.resolve("timers.db");
        try (var timers = new SqliteDurableTimerStore(timerDb)) {
            timers.save(new DurableTimer("t-1", Instant.now(), "wake", Map.of("reason", "deadline")));
        }
        assertStamped(timerDb, "durable_timer");

        var journalDb = tempDir.resolve("effects.db");
        try (var journal = new SqliteEffectJournal(journalDb)) {
            journal.appendPending("run-1", EffectKind.TOOL_CALL, "key-1", "digest");
        }
        assertStamped(journalDb, "effect_journal");

        var runsDb = tempDir.resolve("runs.db");
        try (var runs = new SqliteRunJournal(runsDb)) {
            runs.recordRun(new RunRecord("run-1", "agent", "alice", "sess-1", Instant.now()));
        }
        assertStamped(runsDb, "run_record");

        var tapeDb = tempDir.resolve("tape.db");
        try (var tape = new SqliteTapeStore(tapeDb)) {
            tape.begin(new TapeRun("run-1", "tape-a", "sess-1", "res-1", null, "/chat",
                    "model-1", "rt", 1000L, TapeStatus.OPEN, null, 0, 0, false, null));
        }
        assertStamped(tapeDb, "tape_run");
    }

    // --- helpers -------------------------------------------------------------

    private void assertStamped(Path dbPath, String component) throws SQLException {
        try (var conn = openPath(dbPath)) {
            assertEquals(1, SchemaMigrations.readVersion(conn, component),
                    component + " must stamp its initial schema as version 1");
        }
    }

    private Connection open(String fileName) throws SQLException {
        return openPath(tempDir.resolve(fileName));
    }

    private static Connection openPath(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (var ps = conn.prepareStatement("PRAGMA table_info(" + table + ")");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countRows(Connection conn, String table) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
