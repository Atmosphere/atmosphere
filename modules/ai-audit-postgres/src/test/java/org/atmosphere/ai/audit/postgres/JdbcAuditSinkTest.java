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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link JdbcAuditSink} against an H2 in-memory database. H2
 * accepts enough Postgres dialect to validate the schema auto-create, row
 * serialization, and decision round-trip. A live Postgres integration test
 * belongs in a separate {@code integration-tests} module with Testcontainers.
 */
class JdbcAuditSinkTest {

    private JdbcDataSource ds;

    @BeforeEach
    void setUp() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("SHUTDOWN");
        }
    }

    @Test
    void autoCreatesSchemaAndInsertsEntry() throws Exception {
        var sink = new JdbcAuditSink(ds);
        sink.write(sampleEntry("deny", "matched hijacking probe"));

        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT policy_name, decision, reason, context_snapshot "
                             + "FROM governance_audit_log")) {
            assertTrue(rs.next(), "one row expected");
            assertEquals("scope::support", rs.getString("policy_name"));
            assertEquals("deny", rs.getString("decision"));
            assertEquals("matched hijacking probe", rs.getString("reason"));
            var snapshot = rs.getString("context_snapshot");
            assertNotNull(snapshot);
            assertTrue(snapshot.contains("\"phase\":\"pre_admission\""),
                    "context snapshot JSON contains phase: " + snapshot);
        }
    }

    @Test
    void customTableNameIsHonoured() throws Exception {
        var sink = new JdbcAuditSink(ds, "tenant_audit", true);
        sink.write(sampleEntry("admit", ""));
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM tenant_audit")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void rejectsInvalidTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAuditSink(ds, "DROP TABLE users;", false));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAuditSink(ds, "1badstart", false));
    }

    @Test
    void sinkSurvivesInsertFailure() throws Exception {
        // Table not auto-created and DDL forbidden → insert fails. Sink must
        // swallow the failure so GovernanceDecisionLog's contract holds.
        var sink = new JdbcAuditSink(ds, "missing_table", false);
        sink.write(sampleEntry("admit", "")); // must not throw
    }

    private static AuditEntry sampleEntry(String decision, String reason) {
        return new AuditEntry(
                Instant.now(),
                "scope::support",
                "code:test",
                "1.0",
                decision,
                reason,
                Map.of("phase", "pre_admission", "message", "test"),
                0.5);
    }
}
