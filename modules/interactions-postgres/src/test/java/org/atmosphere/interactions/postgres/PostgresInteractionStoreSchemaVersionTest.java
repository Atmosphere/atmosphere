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
package org.atmosphere.interactions.postgres;

import org.atmosphere.ai.TokenUsage;
import org.atmosphere.interactions.Interaction;
import org.atmosphere.interactions.InteractionStatus;
import org.atmosphere.interactions.SchemaMigrations;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-version stamping for {@link PostgresInteractionStore}, exercised
 * against H2 in PostgreSQL-compatibility mode (the backend the store's
 * behavioural test uses).
 */
class PostgresInteractionStoreSchemaVersionTest {

    private JdbcDataSource ds;
    private String dbName;

    @BeforeEach
    void setUp() {
        dbName = "interaction-versions-" + UUID.randomUUID();
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + dbName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("SHUTDOWN");
        }
    }

    private static Interaction sample(String id) {
        var now = Instant.parse("2026-06-01T12:00:00Z");
        return new Interaction(id, null, "conv-1", "agent-x", "alice", "gpt-4",
                InteractionStatus.COMPLETED, false, true, List.of(),
                "the answer", new TokenUsage(10, 20, 0, 30, "gpt-4"), null, now, now);
    }

    @Test
    void freshDatabaseStampsTheCurrentVersion() throws SQLException {
        var store = new PostgresInteractionStore(ds);
        store.start();
        try {
            store.save(sample("int-1"));
        } finally {
            store.stop();
        }

        assertEquals(1, version());
    }

    @Test
    void legacyTableIsAdoptedAndKeepsItsRows() throws SQLException {
        // A schema created before version stamping existed.
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE interactions (
                    id VARCHAR(255) PRIMARY KEY,
                    parent_id VARCHAR(255),
                    conversation_id VARCHAR(255),
                    agent_id VARCHAR(255),
                    user_id VARCHAR(255),
                    model VARCHAR(255),
                    status VARCHAR(32) NOT NULL,
                    background BOOLEAN NOT NULL,
                    store_flag BOOLEAN NOT NULL,
                    final_text TEXT,
                    usage_json TEXT,
                    error_message TEXT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )""");
            stmt.execute("""
                CREATE TABLE interaction_steps (
                    interaction_id VARCHAR(255) NOT NULL,
                    seq BIGINT NOT NULL,
                    type VARCHAR(64) NOT NULL,
                    text TEXT,
                    tool_name VARCHAR(255),
                    data_json TEXT,
                    usage_json TEXT,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (interaction_id, seq)
                )""");
            stmt.execute("INSERT INTO interactions (id, conversation_id, agent_id, user_id, model,"
                    + " status, background, store_flag, final_text, created_at, updated_at) VALUES"
                    + " ('legacy-1', 'conv-legacy', 'agent-x', 'alice', 'gpt-4', 'COMPLETED',"
                    + " FALSE, TRUE, 'carried over', 1767225600000, 1767225600000)");
        }

        var store = new PostgresInteractionStore(ds);
        store.start();
        try {
            var loaded = store.load("legacy-1").orElseThrow();
            assertEquals("carried over", loaded.finalText());
            store.save(sample("int-new"));
        } finally {
            store.stop();
        }

        assertEquals(1, version(), "an unstamped existing schema is adopted as version 1");
        assertEquals(2, rowCount("interactions"));
    }

    @Test
    void versionNewerThanTheCodeRefusesToStart() throws SQLException {
        var first = new PostgresInteractionStore(ds);
        first.start();
        first.stop();

        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                    + " SET version = 4 WHERE component = 'interactions'");
        }

        var newer = new PostgresInteractionStore(ds);
        var failure = assertThrows(IllegalStateException.class, newer::start);
        assertTrue(failure.getMessage().contains(dbName), failure.getMessage());
        assertTrue(failure.getMessage().contains("version 4"), failure.getMessage());
        assertTrue(failure.getMessage().contains("up to 1"), failure.getMessage());
    }

    @Test
    void restartingAnUpToDateStoreIsANoOp() throws SQLException {
        var first = new PostgresInteractionStore(ds);
        first.start();
        try {
            first.save(sample("int-1"));
        } finally {
            first.stop();
        }

        var second = new PostgresInteractionStore(ds);
        second.start();
        try {
            assertTrue(second.load("int-1").isPresent());
        } finally {
            second.stop();
        }

        assertEquals(1, version());
        assertEquals(1, rowCount("interactions"));
    }

    private int version() throws SQLException {
        try (var conn = ds.getConnection()) {
            return SchemaMigrations.readVersion(conn, "interactions");
        }
    }

    private int rowCount(String table) throws SQLException {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
