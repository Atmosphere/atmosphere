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
package org.atmosphere.ai.audit.postgres;

import org.atmosphere.ai.governance.AuditEntry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-version stamping for {@link JdbcAuditSink}: the sink stamps only the
 * schema it creates itself, and refuses to start against a table stamped newer
 * than this build understands.
 */
class JdbcAuditSinkSchemaVersionTest {

    private JdbcDataSource ds;
    private String dbName;

    @BeforeEach
    void setUp() {
        dbName = "audit-versions-" + UUID.randomUUID();
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
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

    private static AuditEntry entry() {
        return new AuditEntry(Instant.parse("2026-07-01T10:00:00Z"), "policy-a", "inline",
                "1", "admit", "", Map.of("agent", "alpha"), 1.5);
    }

    @Test
    void autoCreatedSchemaIsStampedAndStillAcceptsWrites() throws SQLException {
        var sink = new JdbcAuditSink(ds, "governance_audit_log", true);
        sink.write(entry());

        assertEquals(1, version("governance_audit_log"));
        assertEquals(1, rowCount("governance_audit_log"));
    }

    @Test
    void rebuildingTheSinkOverAStampedTableIsANoOp() throws SQLException {
        new JdbcAuditSink(ds, "governance_audit_log", true).write(entry());
        var second = new JdbcAuditSink(ds, "governance_audit_log", true);
        second.write(entry());

        assertEquals(1, version("governance_audit_log"));
        assertEquals(2, rowCount("governance_audit_log"));
    }

    @Test
    void distinctTablesVersionIndependently() throws SQLException {
        new JdbcAuditSink(ds, "governance_audit_log", true).write(entry());
        new JdbcAuditSink(ds, "governance_audit_archive", true).write(entry());

        assertEquals(1, version("governance_audit_log"));
        assertEquals(1, version("governance_audit_archive"));
    }

    @Test
    void versionNewerThanTheCodeRefusesToBuildTheSink() throws SQLException {
        new JdbcAuditSink(ds, "governance_audit_log", true);

        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("UPDATE " + SchemaMigrations.VERSION_TABLE
                    + " SET version = 8 WHERE component = 'governance_audit_log'");
        }

        var failure = assertThrows(IllegalStateException.class,
                () -> new JdbcAuditSink(ds, "governance_audit_log", true));
        assertTrue(failure.getMessage().contains(dbName), failure.getMessage());
        assertTrue(failure.getMessage().contains("version 8"), failure.getMessage());
        assertTrue(failure.getMessage().contains("up to 1"), failure.getMessage());
    }

    @Test
    void externallyManagedDdlIsNeitherCreatedNorStamped() throws SQLException {
        // autoCreate=false means Flyway/Liquibase owns the schema — the sink
        // must not write DDL, and must not stamp a schema it does not own.
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE governance_audit_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "ts TIMESTAMP NOT NULL, "
                    + "policy_name VARCHAR(255) NOT NULL, "
                    + "policy_source VARCHAR(512) NOT NULL, "
                    + "policy_version VARCHAR(64) NOT NULL, "
                    + "decision VARCHAR(32) NOT NULL, "
                    + "reason VARCHAR(4000), "
                    + "evaluation_ms DOUBLE PRECISION NOT NULL, "
                    + "context_snapshot CLOB NOT NULL)");
        }

        var sink = new JdbcAuditSink(ds, "governance_audit_log", false);
        sink.write(entry());

        assertEquals(1, rowCount("governance_audit_log"), "writes still land");
        assertFalse(versionTableExists(), "no version table for an operator-owned schema");
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

    private boolean versionTableExists() throws SQLException {
        try (var conn = ds.getConnection();
             var rs = conn.getMetaData().getTables(null, null,
                     SchemaMigrations.VERSION_TABLE.toUpperCase(java.util.Locale.ROOT),
                     new String[] {"TABLE"})) {
            return rs.next();
        }
    }
}
