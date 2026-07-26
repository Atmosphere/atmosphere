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
package org.atmosphere.interactions;

import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema-version stamping and legacy adoption for {@link SqliteInteractionStore}. */
class SqliteInteractionStoreSchemaVersionTest {

    @TempDir
    Path tempDir;

    private static Interaction sample(String id) {
        var now = Instant.parse("2026-06-01T12:00:00Z");
        return new Interaction(id, null, "conv-1", "agent-x", "alice", "gpt-4",
                InteractionStatus.COMPLETED, false, true, List.of(),
                "the answer", new TokenUsage(10, 20, 0, 30, "gpt-4"), null, now, now);
    }

    @Test
    void freshDatabaseStampsTheCurrentVersion() throws SQLException {
        var dbPath = tempDir.resolve("interactions.db");
        var store = new SqliteInteractionStore(dbPath);
        store.start();
        try {
            store.save(sample("int-1"));
        } finally {
            store.stop();
        }

        assertVersion(dbPath, 1);
    }

    @Test
    void legacyDatabaseIsAdoptedAndKeepsItsRows() throws SQLException {
        var dbPath = tempDir.resolve("legacy-interactions.db");
        // A database written before version stamping existed.
        try (var conn = open(dbPath);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE interactions (
                    id TEXT PRIMARY KEY,
                    parent_id TEXT,
                    conversation_id TEXT,
                    agent_id TEXT,
                    user_id TEXT,
                    model TEXT,
                    status TEXT NOT NULL,
                    background INTEGER NOT NULL,
                    store_flag INTEGER NOT NULL,
                    final_text TEXT,
                    usage_json TEXT,
                    error_message TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");
            stmt.execute("""
                CREATE TABLE interaction_steps (
                    interaction_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    text TEXT,
                    tool_name TEXT,
                    data_json TEXT,
                    usage_json TEXT,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (interaction_id, seq)
                )""");
            stmt.execute("INSERT INTO interactions (id, conversation_id, agent_id, user_id, model,"
                    + " status, background, store_flag, final_text, created_at, updated_at) VALUES"
                    + " ('legacy-1', 'conv-legacy', 'agent-x', 'alice', 'gpt-4', 'COMPLETED',"
                    + " 0, 1, 'carried over', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
        }

        var store = new SqliteInteractionStore(dbPath);
        store.start();
        try {
            var loaded = store.load("legacy-1").orElseThrow();
            assertEquals("carried over", loaded.finalText());
            assertEquals("alice", loaded.userId());
            store.save(sample("int-new"));
        } finally {
            store.stop();
        }

        assertVersion(dbPath, 1);
        assertEquals(2, rowCount(dbPath, "interactions"));
    }

    @Test
    void versionNewerThanTheCodeRefusesToStart() throws SQLException {
        var dbPath = tempDir.resolve("future-interactions.db");
        var first = new SqliteInteractionStore(dbPath);
        first.start();
        first.stop();

        try (var conn = open(dbPath);
             var stmt = conn.createStatement()) {
            stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                    + " SET version = 3 WHERE component = 'interactions'");
        }

        var newer = new SqliteInteractionStore(dbPath);
        var failure = assertThrows(IllegalStateException.class, newer::start);
        assertTrue(failure.getMessage().contains("future-interactions.db"), failure.getMessage());
        assertTrue(failure.getMessage().contains("version 3"), failure.getMessage());
        assertTrue(failure.getMessage().contains("up to 1"), failure.getMessage());
        newer.stop();
    }

    @Test
    void reopeningAStampedDatabaseIsANoOp() throws SQLException {
        var dbPath = tempDir.resolve("reopen-interactions.db");
        var first = new SqliteInteractionStore(dbPath);
        first.start();
        try {
            first.save(sample("int-1"));
        } finally {
            first.stop();
        }

        var second = new SqliteInteractionStore(dbPath);
        second.start();
        try {
            assertTrue(second.load("int-1").isPresent());
        } finally {
            second.stop();
        }

        assertVersion(dbPath, 1);
        assertEquals(1, rowCount(dbPath, "interactions"));
    }

    private static void assertVersion(Path dbPath, int expected) throws SQLException {
        try (var conn = open(dbPath)) {
            assertEquals(expected, SchemaMigrations.readVersion(conn, "interactions"));
        }
    }

    private static int rowCount(Path dbPath, String table) throws SQLException {
        try (var conn = open(dbPath);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Connection open(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }
}
