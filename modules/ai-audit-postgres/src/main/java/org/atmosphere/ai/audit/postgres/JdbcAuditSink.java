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
import org.atmosphere.ai.governance.AuditJsonEncoder;
import org.atmosphere.ai.governance.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * {@link AuditSink} that writes {@link AuditEntry} rows to a JDBC table.
 * Targets Postgres (the {@code context_snapshot} column is declared
 * {@code JSONB} on Postgres, {@code CLOB} elsewhere) but works against any
 * JSR-221 {@link DataSource} — tests exercise the sink against an H2
 * in-memory database.
 *
 * <h2>Schema</h2>
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS <table> (
 *     id               BIGSERIAL PRIMARY KEY,
 *     ts               TIMESTAMP WITH TIME ZONE NOT NULL,
 *     policy_name      VARCHAR(255) NOT NULL,
 *     policy_source    VARCHAR(512) NOT NULL,
 *     policy_version   VARCHAR(64)  NOT NULL,
 *     decision         VARCHAR(32)  NOT NULL,
 *     reason           TEXT         NOT NULL,
 *     evaluation_ms    DOUBLE PRECISION NOT NULL,
 *     context_snapshot JSONB        NOT NULL
 * );
 * CREATE INDEX IF NOT EXISTS idx_<table>_ts_policy
 *     ON <table> (ts DESC, policy_name);
 * }</pre>
 *
 * <p>Wrap with {@link org.atmosphere.ai.governance.AsyncAuditSink} in
 * production so admission threads never block on IO — JDBC inserts can
 * stall on connection-pool exhaustion.</p>
 */
public final class JdbcAuditSink implements AuditSink {

    private static final Logger logger = LoggerFactory.getLogger(JdbcAuditSink.class);

    private final DataSource dataSource;
    private final String table;
    private final String insertSql;

    /** Build against the default table name {@code governance_audit_log}. */
    public JdbcAuditSink(DataSource dataSource) {
        this(dataSource, "governance_audit_log", true);
    }

    /**
     * @param dataSource   JDBC source; the sink never closes it (owned by caller)
     * @param table        table name to insert into
     * @param autoCreate   when {@code true}, {@code CREATE TABLE IF NOT EXISTS}
     *                     runs on construction; set {@code false} when DDL is
     *                     managed by Flyway / Liquibase / platform migrations
     */
    public JdbcAuditSink(DataSource dataSource, String table, boolean autoCreate) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "table must be a simple identifier (alphanumeric + underscore), got: " + table);
        }
        this.dataSource = dataSource;
        this.table = table;
        this.insertSql = "INSERT INTO " + table
                + " (ts, policy_name, policy_source, policy_version, decision, reason,"
                + " evaluation_ms, context_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        if (autoCreate) {
            createSchemaIfMissing();
        }
    }

    @Override
    public void write(AuditEntry entry) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(insertSql)) {
            stmt.setTimestamp(1, Timestamp.from(entry.timestamp()));
            stmt.setString(2, entry.policyName());
            stmt.setString(3, entry.policySource());
            stmt.setString(4, entry.policyVersion());
            stmt.setString(5, entry.decision());
            stmt.setString(6, entry.reason());
            stmt.setDouble(7, entry.evaluationMs());
            stmt.setString(8, AuditJsonEncoder.encode(entry));
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("JDBC audit insert into '{}' failed: {}", table, e.toString());
            // Swallowed so GovernanceDecisionLog's sink-failure contract
            // holds — one bad sink does not propagate. The fail-closed
            // variant is the operator's choice, not the sink's.
        }
    }

    @Override
    public String name() {
        return "jdbc:" + table;
    }

    private void createSchemaIfMissing() {
        var ddl = createTableDdl(detectDialect());
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            for (var statement : ddl) {
                stmt.execute(statement);
            }
        } catch (SQLException e) {
            logger.warn("Schema auto-create for '{}' failed — assuming external DDL: {}",
                    table, e.toString());
        }
    }

    private String detectDialect() {
        try (var conn = dataSource.getConnection()) {
            var product = conn.getMetaData().getDatabaseProductName();
            return product == null ? "generic" : product.toLowerCase();
        } catch (SQLException e) {
            return "generic";
        }
    }

    private String[] createTableDdl(String dialect) {
        var isPostgres = dialect.contains("postgres");
        var json = isPostgres ? "JSONB" : "CLOB";
        var idAuto = isPostgres ? "BIGSERIAL" : "BIGINT AUTO_INCREMENT";
        var ts = isPostgres ? "TIMESTAMP WITH TIME ZONE" : "TIMESTAMP";
        return new String[] {
                "CREATE TABLE IF NOT EXISTS " + table + " ("
                        + "id " + idAuto + " PRIMARY KEY, "
                        + "ts " + ts + " NOT NULL, "
                        + "policy_name VARCHAR(255) NOT NULL, "
                        + "policy_source VARCHAR(512) NOT NULL, "
                        + "policy_version VARCHAR(64) NOT NULL, "
                        + "decision VARCHAR(32) NOT NULL, "
                        + "reason VARCHAR(4000), "
                        + "evaluation_ms DOUBLE PRECISION NOT NULL, "
                        + "context_snapshot " + json + " NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_" + table
                        + "_ts_policy ON " + table + " (ts DESC, policy_name)"
        };
    }
}
