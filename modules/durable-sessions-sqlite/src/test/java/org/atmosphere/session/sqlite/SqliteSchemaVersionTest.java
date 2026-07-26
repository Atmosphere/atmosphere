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
package org.atmosphere.session.sqlite;

import org.atmosphere.session.DurableSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-version stamping for the three durable-sessions SQLite stores. Each
 * store keys its version row by its own table, so the trio can share one
 * database file and still version independently.
 */
class SqliteSchemaVersionTest {

    @TempDir
    Path tempDir;

    @Test
    void freshStoresStampTheirInitialSchema() throws SQLException {
        var sessionsPath = tempDir.resolve("sessions.db");
        var sessions = new SqliteSessionStore(sessionsPath);
        try {
            sessions.save(DurableSession.create("tok-1", "res-1"));
        } finally {
            sessions.close();
        }
        assertVersion(sessionsPath, "durable_sessions", 1);

        var convPath = tempDir.resolve("conversations.db");
        var conversations = new SqliteConversationPersistence(convPath);
        try {
            conversations.save("conv-1", "{\"messages\":[]}");
        } finally {
            conversations.close();
        }
        assertVersion(convPath, "ai_conversations", 1);

        var factsPath = tempDir.resolve("facts.db");
        var facts = new SqliteLongTermMemory(factsPath, 10);
        try {
            facts.saveFact("alice", "likes tea");
        } finally {
            facts.close();
        }
        assertVersion(factsPath, "ai_user_facts", 1);
    }

    @Test
    void storesSharingOneDatabaseVersionIndependently() throws SQLException {
        var dbPath = tempDir.resolve("shared.db");
        var sessions = new SqliteSessionStore(dbPath);
        var conversations = new SqliteConversationPersistence(dbPath);
        var facts = new SqliteLongTermMemory(dbPath, 10);
        try {
            sessions.save(DurableSession.create("tok-1", "res-1"));
            conversations.save("conv-1", "{}");
            facts.saveFact("alice", "likes tea");
        } finally {
            facts.close();
            conversations.close();
            sessions.close();
        }

        assertVersion(dbPath, "durable_sessions", 1);
        assertVersion(dbPath, "ai_conversations", 1);
        assertVersion(dbPath, "ai_user_facts", 1);
    }

    @Test
    void legacyDatabaseIsAdoptedAndKeepsItsRows() throws SQLException {
        var dbPath = tempDir.resolve("legacy-conversations.db");
        // A database written before version stamping existed.
        try (var conn = open(dbPath);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE ai_conversations (
                        conversation_id TEXT PRIMARY KEY,
                        data            TEXT NOT NULL,
                        updated_at      INTEGER NOT NULL
                    )
                    """);
            stmt.execute("INSERT INTO ai_conversations VALUES ('conv-legacy', '{\"kept\":true}', 1)");
        }

        var conversations = new SqliteConversationPersistence(dbPath);
        try {
            var loaded = conversations.load("conv-legacy");
            assertTrue(loaded.isPresent(), "the legacy row must survive adoption");
            assertEquals("{\"kept\":true}", loaded.get());
            conversations.save("conv-new", "{}");
        } finally {
            conversations.close();
        }

        assertVersion(dbPath, "ai_conversations", 1);
    }

    @Test
    void versionNewerThanTheCodeRefusesToOpen() throws SQLException {
        var dbPath = tempDir.resolve("future-sessions.db");
        new SqliteSessionStore(dbPath).close();

        try (var conn = open(dbPath);
             var stmt = conn.createStatement()) {
            stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                    + " SET version = 5 WHERE component = 'durable_sessions'");
        }

        var failure = assertThrows(IllegalStateException.class,
                () -> new SqliteSessionStore(dbPath));
        assertTrue(failure.getMessage().contains("future-sessions.db"), failure.getMessage());
        assertTrue(failure.getMessage().contains("version 5"), failure.getMessage());
        assertTrue(failure.getMessage().contains("up to 1"), failure.getMessage());
    }

    @Test
    void reopeningAStampedDatabaseIsANoOp() throws SQLException {
        var dbPath = tempDir.resolve("reopen-facts.db");
        var facts = new SqliteLongTermMemory(dbPath, 10);
        try {
            facts.saveFact("alice", "likes tea");
        } finally {
            facts.close();
        }

        var reopened = new SqliteLongTermMemory(dbPath, 10);
        try {
            assertEquals(1, reopened.getFacts("alice", 10).size());
            reopened.saveFact("alice", "drinks it black");
            assertEquals(2, reopened.getFacts("alice", 10).size());
        } finally {
            reopened.close();
        }

        assertVersion(dbPath, "ai_user_facts", 1);
    }

    @Test
    void reopenedSessionStoreStillRestoresItsRows() throws SQLException {
        var dbPath = tempDir.resolve("reopen-sessions.db");
        var first = new SqliteSessionStore(dbPath);
        try {
            first.save(DurableSession.create("tok-1", "res-1"));
        } finally {
            first.close();
        }

        var second = new SqliteSessionStore(dbPath);
        try {
            assertTrue(second.restore("tok-1").isPresent());
        } finally {
            second.close();
        }

        assertVersion(dbPath, "durable_sessions", 1);
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
