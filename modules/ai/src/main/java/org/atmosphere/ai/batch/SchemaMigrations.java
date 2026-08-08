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
package org.atmosphere.ai.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Schema-version stamping and ordered, idempotent migrations for Atmosphere's
 * JDBC-backed stores.
 *
 * <h2>Convention</h2>
 *
 * Each store stamps its schema version in a shared
 * {@code atmosphere_schema_version (component, version)} table, keyed by the
 * store's <em>anchor table</em> name (the table its version-1 schema creates).
 * A per-component row — rather than a database-global marker such as SQLite's
 * {@code PRAGMA user_version} — is deliberate: several stores legitimately
 * share one database (the durable-sessions trio can share a connection, the
 * admin eval stores share a file, and the Postgres stores share a database
 * with configurable table names), and their schema versions advance
 * independently.
 *
 * <p>On open the store calls {@link #migrate} with its ordered step list,
 * where {@code steps.get(n)} brings the schema from version {@code n} to
 * {@code n + 1} and {@code steps.size()} is the version the code understands:</p>
 *
 * <ul>
 *   <li><b>Fresh database</b> — no version row, anchor table absent: every
 *       step runs, the final version is stamped.</li>
 *   <li><b>Legacy adoption</b> — no version row but the anchor table exists
 *       (the database predates version stamping): it is adopted as version 1
 *       and only later steps run. Because an unstamped database may have been
 *       written by <em>any</em> earlier build, steps after the first must be
 *       idempotent (probe before {@code ALTER}, or use
 *       {@code ... IF NOT EXISTS}).</li>
 *   <li><b>Up to date</b> — stamped version equals {@code steps.size()}:
 *       nothing runs; the open is read-only.</li>
 *   <li><b>Newer than the code</b> — stamped version exceeds
 *       {@code steps.size()}: the store <b>refuses to open</b> with an
 *       {@link IllegalStateException} naming the database, the found version,
 *       and the supported version. Fail closed: reading a newer schema with
 *       older code risks silent data mis-reads (Correctness Invariant #6).</li>
 * </ul>
 *
 * <p>Each step and its version stamp commit in one transaction (both SQLite
 * and Postgres/H2 run DDL transactionally), so a crash mid-migration leaves a
 * consistent already-applied prefix that the next open resumes from. The
 * caller's auto-commit mode is restored on every exit path. Two processes
 * racing the very first stamp can collide on the version-row insert — one
 * fails with a duplicate-key {@link SQLException} and simply retries; the
 * steps themselves are idempotent.</p>
 *
 * <p><b>Keep the copies in sync:</b> this is a package-local copy of the
 * canonical {@code org.atmosphere.checkpoint.SchemaMigrations} — copied, not
 * depended on, because this module must not gain a dependency on
 * {@code atmosphere-checkpoint}. Semantics are identical by construction; a
 * semantic change in the canonical class must be mirrored here, and
 * {@code SchemaMigrationsCopySyncTest} fails the build on drift.</p>
 *
 * @since 4.0
 */
final class SchemaMigrations {

    /** Table every store stamps its schema version into, one row per component. */
    public static final String VERSION_TABLE = "atmosphere_schema_version";

    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrations.class);

    /** One schema migration: brings a store's schema from version N to N + 1. */
    @FunctionalInterface
    public interface MigrationStep {
        /**
         * Apply this step's DDL/DML on the given connection. Runs inside the
         * migration transaction; do not commit, roll back, or toggle
         * auto-commit here.
         */
        void apply(Connection connection) throws SQLException;
    }

    private SchemaMigrations() {
    }

    /**
     * Read the stamped version, apply the missing migration steps in order,
     * and stamp the resulting version — see the class Javadoc for the exact
     * fresh / legacy-adopt / up-to-date / newer-than-code semantics.
     *
     * @param connection the store's connection (SQLite, Postgres, or H2); its
     *                   auto-commit mode is restored before returning
     * @param component  the store's anchor table name — both the version-row
     *                   key and the table probed for legacy adoption
     * @param steps      ordered steps where {@code steps.get(n)} migrates
     *                   version {@code n} to {@code n + 1}; must not be empty
     * @throws IllegalStateException when the stamped version is newer than
     *                               {@code steps.size()} (refuse to open)
     * @throws SQLException          when a step or the stamping itself fails
     */
    public static void migrate(Connection connection, String component,
                               List<MigrationStep> steps) throws SQLException {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        var previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (var stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " ("
                        + "component VARCHAR(255) PRIMARY KEY, "
                        + "version INTEGER NOT NULL)");
            }
            connection.commit();

            int stamped = readVersion(connection, component);
            int target = steps.size();
            if (stamped > target) {
                throw new IllegalStateException("Schema for '" + component + "' at "
                        + describe(connection) + " is stamped version " + stamped
                        + " but this build understands only versions up to " + target
                        + " — refusing to open: reading a newer schema with older code"
                        + " risks silent data mis-reads. Upgrade the application or point"
                        + " it at a database written by a matching version.");
            }
            int from;
            if (stamped >= 0) {
                from = stamped;
            } else if (tableExists(connection, component)) {
                // Unstamped database whose tables already exist: it predates
                // version stamping. Adopt it as version 1 (the initial schema)
                // and apply only the later steps.
                from = 1;
            } else {
                from = 0;
            }
            for (int version = from; version < target; version++) {
                steps.get(version).apply(connection);
                stampVersion(connection, component, version + 1);
                connection.commit();
            }
            if (stamped < 0 && from == target) {
                // Legacy adoption with no later steps to run: stamp the
                // adopted version so future opens skip the probe.
                stampVersion(connection, component, target);
                connection.commit();
            }
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            // Restore on the failure path too — a pooled connection handed back
            // with auto-commit off silently swallows a later caller's writes.
            // Suppress a restore failure so it cannot mask the real cause.
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreFailure) {
                e.addSuppressed(restoreFailure);
            }
            throw e;
        }
        // Success path: a failed restore is itself a defect worth propagating,
        // not something to hide in a finally block.
        connection.setAutoCommit(previousAutoCommit);
    }

    /**
     * The stamped version for {@code component}, or {@code -1} when no row
     * exists (fresh database or one that predates version stamping).
     */
    public static int readVersion(Connection connection, String component) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT version FROM " + VERSION_TABLE + " WHERE component = ?")) {
            ps.setString(1, component);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /**
     * Portable upsert of the version row: DELETE-then-INSERT inside the
     * caller's open transaction (no Postgres-only {@code ON CONFLICT}), the
     * same portability trick {@code PostgresCheckpointStore#save} uses.
     */
    private static void stampVersion(Connection connection, String component,
                                     int version) throws SQLException {
        try (var del = connection.prepareStatement(
                "DELETE FROM " + VERSION_TABLE + " WHERE component = ?")) {
            del.setString(1, component);
            del.executeUpdate();
        }
        try (var ins = connection.prepareStatement(
                "INSERT INTO " + VERSION_TABLE + " (component, version) VALUES (?, ?)")) {
            ins.setString(1, component);
            ins.setInt(2, version);
            ins.executeUpdate();
        }
    }

    /**
     * Whether {@code table} exists, via JDBC metadata. Tries the literal name
     * plus upper/lower-case foldings so unquoted identifiers match on SQLite
     * (as-is), H2 (upper-cased), and Postgres (lower-cased).
     */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        var meta = connection.getMetaData();
        var candidates = new String[] {
                table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)};
        for (var candidate : candidates) {
            try (var rs = meta.getTables(null, null, candidate, new String[] {"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The connection's JDBC URL for error messages, never throwing. */
    private static String describe(Connection connection) {
        try {
            var url = connection.getMetaData().getURL();
            return url != null ? url : "<unknown database>";
        } catch (SQLException e) {
            logger.trace("Could not read JDBC URL for a migration error message", e);
            return "<unknown database>";
        }
    }
}
