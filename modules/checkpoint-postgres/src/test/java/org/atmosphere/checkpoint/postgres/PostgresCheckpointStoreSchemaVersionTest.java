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
package org.atmosphere.checkpoint.postgres;

import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.SchemaMigrations;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-version stamping and migration for {@link PostgresCheckpointStore},
 * exercised against H2 in PostgreSQL-compatibility mode — the same backend the
 * store's behavioural test uses, so the portable DDL is validated on the JDBC
 * path as well as the SQLite one.
 */
class PostgresCheckpointStoreSchemaVersionTest {

    private JdbcDataSource ds;
    private String dbName;

    @BeforeEach
    void setUp() {
        dbName = "checkpoint-versions-" + UUID.randomUUID();
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

    @Test
    void freshDatabaseStampsTheCurrentVersion() throws SQLException {
        var store = new PostgresCheckpointStore(ds);
        store.start();
        try {
            store.save(WorkflowSnapshot.root("coord-1", "state"));
        } finally {
            store.stop();
        }

        assertEquals(2, version("checkpoints"));
    }

    @Test
    void legacyTableIsAdoptedMigratedAndKeepsItsRows() throws SQLException {
        // The pre-state_type schema, written by an older build.
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE checkpoints ("
                    + "id VARCHAR(255) PRIMARY KEY, "
                    + "parent_id VARCHAR(255), "
                    + "coordination_id VARCHAR(255) NOT NULL, "
                    + "agent_name VARCHAR(255), "
                    + "state_json TEXT, "
                    + "metadata_json TEXT, "
                    + "created_at BIGINT NOT NULL)");
            stmt.execute("INSERT INTO checkpoints "
                    + "(id, coordination_id, state_json, metadata_json, created_at) VALUES "
                    + "('legacy-1', 'coord-legacy', '\"carried over\"', '{}', "
                    + Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() + ")");
        }

        var store = new PostgresCheckpointStore(ds);
        store.start();
        try {
            var loaded = store.load(CheckpointId.of("legacy-1"));
            assertTrue(loaded.isPresent(), "the legacy row must survive adoption");
            assertEquals("carried over", loaded.get().state());

            store.save(WorkflowSnapshot.root("coord-new", "after migration"));
        } finally {
            store.stop();
        }

        assertEquals(2, version("checkpoints"), "adopted as 1, migrated to 2");
        assertTrue(hasColumn("checkpoints", "state_type"), "the 1 -> 2 step must add state_type");
        assertEquals(2, rowCount("checkpoints"));
    }

    @Test
    void versionNewerThanTheCodeRefusesToStart() throws SQLException {
        var first = new PostgresCheckpointStore(ds);
        first.start();
        first.stop();

        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                    + " SET version = 42 WHERE component = 'checkpoints'");
        }

        var newer = new PostgresCheckpointStore(ds);
        var failure = assertThrows(IllegalStateException.class, newer::start);
        assertTrue(failure.getMessage().contains(dbName),
                "must name the database: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("version 42"),
                "must report the found version: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("up to 2"),
                "must report the supported version: " + failure.getMessage());
    }

    @Test
    void restartingAnUpToDateStoreIsANoOp() throws SQLException {
        var first = new PostgresCheckpointStore(ds);
        first.start();
        var saved = first.save(WorkflowSnapshot.root("coord-1", "kept"));
        first.stop();

        var second = new PostgresCheckpointStore(ds);
        second.start();
        try {
            assertTrue(second.load(saved.id()).isPresent());
        } finally {
            second.stop();
        }

        assertEquals(2, version("checkpoints"));
        assertEquals(1, rowCount("checkpoints"));
    }

    @Test
    void distinctTablesInOneDatabaseVersionIndependently() throws SQLException {
        var main = new PostgresCheckpointStore(ds);
        main.start();
        main.stop();

        var other = new PostgresCheckpointStore(ds, "checkpoints_archive");
        other.start();
        other.stop();

        assertEquals(2, version("checkpoints"));
        assertEquals(2, version("checkpoints_archive"));
    }

    private int version(String component) throws SQLException {
        try (var conn = ds.getConnection()) {
            return SchemaMigrations.readVersion(conn, component);
        }
    }

    private int rowCount(String table) throws SQLException {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (var conn = ds.getConnection();
             var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
