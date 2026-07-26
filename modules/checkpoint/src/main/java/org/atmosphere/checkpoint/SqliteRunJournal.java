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

import org.atmosphere.ai.resume.RunEvent;
import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.ai.resume.RunJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Crash-durable {@link RunJournal} backed by an embedded SQLite database — the
 * run-resume counterpart to {@link SqliteEffectJournal}. The bundled
 * {@link org.atmosphere.ai.resume.InMemoryRunJournal} rehydrates a fresh
 * {@code RunRegistry} only within one JVM; this backend persists run metadata
 * and captured events to disk so a registry built over the same database file
 * after a crash or rolling redeploy replays what each run produced
 * (crash-durable resume). {@link #durable()} therefore returns {@code true}.
 *
 * <h2>Schema</h2>
 *
 * <ul>
 *   <li>{@code run_record} — one row per journaled run (the rehydration set);
 *       retention evicts the oldest run by {@code created_at} past
 *       {@code maxRuns}.</li>
 *   <li>{@code run_event} — the per-run event log, {@code PRIMARY KEY
 *       (run_id, sequence)} preserving the capture order replay depends on;
 *       bounded per run at {@code maxEventsPerRun}, oldest {@code sequence}
 *       evicted first (the same ring semantics as
 *       {@link RunEventReplayBuffer}).</li>
 * </ul>
 *
 * <h2>Ownership (Correctness Invariant #1)</h2>
 *
 * The journal opens and owns its own {@link Connection}; {@link #close()} closes
 * it. It never touches a connection it did not create.
 *
 * <h2>Best-effort writes (Correctness Invariant #3)</h2>
 *
 * {@link #recordRun}, {@link #appendEvent} and {@link #removeRun} run on the
 * streaming hot path and are best-effort per the {@link RunJournal} contract: a
 * write failure is logged at TRACE and never thrown into the live stream — the
 * run simply falls back to in-memory-only replay. {@link #loadAll} and
 * {@link #loadEvents} run once at boot (rehydration) and surface failures so a
 * corrupt journal cannot masquerade as a healthy crash-durable store
 * (Correctness Invariant #5).
 *
 * @since 4.0
 */
public final class SqliteRunJournal implements RunJournal, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SqliteRunJournal.class);

    private final Connection connection;
    private final int maxRuns;
    private final int maxEventsPerRun;
    private final ReentrantLock lock = new ReentrantLock();

    /** Open at the given file with default bounds. */
    public SqliteRunJournal(Path dbPath) {
        this(dbPath, org.atmosphere.ai.resume.InMemoryRunJournal.DEFAULT_MAX_RUNS,
                RunEventReplayBuffer.DEFAULT_CAPACITY);
    }

    /** Open at the given file with explicit bounds. */
    public SqliteRunJournal(Path dbPath, int maxRuns, int maxEventsPerRun) {
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be > 0, got " + maxRuns);
        }
        if (maxEventsPerRun <= 0) {
            throw new IllegalArgumentException("maxEventsPerRun must be > 0, got " + maxEventsPerRun);
        }
        this.maxRuns = maxRuns;
        this.maxEventsPerRun = maxEventsPerRun;
        try {
            this.connection = DriverManager.getConnection(toJdbcUrl(dbPath));
            try {
                connection.setAutoCommit(true);
                createSchema();
            } catch (SQLException | RuntimeException e) {
                // The journal never escapes the failed constructor, so close
                // the connection it created (Correctness Invariant #2) —
                // including on a schema-version refusal, which propagates as-is.
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite run journal: " + dbPath, e);
        }
    }

    private static String toJdbcUrl(Path dbPath) {
        var abs = dbPath.toAbsolutePath();
        var parent = abs.getParent();
        if (parent != null) {
            try {
                java.nio.file.Files.createDirectories(parent);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot create directory: " + parent, e);
            }
        }
        return "jdbc:sqlite:" + abs;
    }

    private void createSchema() throws SQLException {
        SchemaMigrations.migrate(connection, "run_record", List.of(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS run_record (
                        run_id TEXT PRIMARY KEY,
                        agent_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS run_event (
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        ts TEXT NOT NULL,
                        PRIMARY KEY (run_id, sequence)
                    )""");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_run_record_created "
                        + "ON run_record(created_at)");
            }
        }));
    }

    @Override
    public void recordRun(RunRecord run) {
        Objects.requireNonNull(run, "run");
        lock.lock();
        try {
            try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO run_record "
                    + "(run_id, agent_id, user_id, session_id, created_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, run.runId());
                ps.setString(2, run.agentId());
                ps.setString(3, run.userId());
                ps.setString(4, run.sessionId());
                ps.setString(5, run.createdAt().toString());
                ps.executeUpdate();
            }
            evictOldestRunIfOverCapacity();
        } catch (SQLException e) {
            // Best-effort: a journal failure must not break the live run.
            logger.trace("Failed to record run {} in the SQLite run journal", run.runId(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void appendEvent(String runId, RunEvent event) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(event, "event");
        lock.lock();
        try {
            // Only journal events for runs we know about — an event for an
            // unrecorded run (e.g. one already swept) is dropped rather than
            // resurrecting a half-run with no metadata.
            if (!runExists(runId)) {
                return;
            }
            try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO run_event "
                    + "(run_id, sequence, type, payload, ts) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, runId);
                ps.setLong(2, event.sequence());
                ps.setString(3, event.type());
                ps.setString(4, event.payload());
                ps.setString(5, event.timestamp().toString());
                ps.executeUpdate();
            }
            evictOldestEventsIfOverCapacity(runId);
        } catch (SQLException e) {
            logger.trace("Failed to append event {} for run {} in the SQLite run journal",
                    event.sequence(), runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void removeRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        lock.lock();
        try {
            deleteRun(runId);
        } catch (SQLException e) {
            // Best-effort terminal cleanup: retention will evict the run later.
            logger.trace("Failed to remove run {} from the SQLite run journal", runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RunRecord> loadAll() {
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT run_id, agent_id, user_id, "
                + "session_id, created_at FROM run_record")) {
            var out = new ArrayList<RunRecord>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(recordFromRow(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load runs from the SQLite run journal", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RunEvent> loadEvents(String runId) {
        Objects.requireNonNull(runId, "runId");
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT sequence, type, payload, ts "
                + "FROM run_event WHERE run_id = ? ORDER BY sequence ASC")) {
            ps.setString(1, runId);
            var out = new ArrayList<RunEvent>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RunEvent(
                            rs.getLong("sequence"),
                            rs.getString("type"),
                            rs.getString("payload"),
                            Instant.parse(rs.getString("ts"))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load events for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean durable() {
        return true;
    }

    /** Visible for tests / admin: number of runs currently journaled. */
    public int runCount() {
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM run_record");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count runs", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            logger.warn("Error closing SQLite run journal connection", e);
        } finally {
            lock.unlock();
        }
    }

    // --- helpers (all called under the lock) ---------------------------------

    private boolean runExists(String runId) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT 1 FROM run_record WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void deleteRun(String runId) throws SQLException {
        try (var ps = connection.prepareStatement("DELETE FROM run_event WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
        try (var ps = connection.prepareStatement("DELETE FROM run_record WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
    }

    private void evictOldestRunIfOverCapacity() throws SQLException {
        int total;
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM run_record");
             var rs = ps.executeQuery()) {
            total = rs.next() ? rs.getInt(1) : 0;
        }
        if (total <= maxRuns) {
            return;
        }
        // Mirror InMemoryRunJournal: evict the oldest run(s) by created_at so an
        // abandoned-run leak cannot exhaust the store even if removeRun is never
        // called (Correctness Invariant #3).
        var victims = new ArrayList<String>();
        try (var ps = connection.prepareStatement("SELECT run_id FROM run_record "
                + "ORDER BY created_at ASC, run_id ASC LIMIT ?")) {
            ps.setInt(1, total - maxRuns);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    victims.add(rs.getString("run_id"));
                }
            }
        }
        for (var victim : victims) {
            deleteRun(victim);
        }
    }

    private void evictOldestEventsIfOverCapacity(String runId) throws SQLException {
        int total;
        try (var ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM run_event WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                total = rs.next() ? rs.getInt(1) : 0;
            }
        }
        if (total <= maxEventsPerRun) {
            return;
        }
        // Ring semantics: drop the lowest-sequence (oldest) events past the cap,
        // matching RunEventReplayBuffer so replay fidelity mirrors the live buffer.
        try (var ps = connection.prepareStatement("DELETE FROM run_event WHERE run_id = ? "
                + "AND sequence IN (SELECT sequence FROM run_event WHERE run_id = ? "
                + "ORDER BY sequence ASC LIMIT ?)")) {
            ps.setString(1, runId);
            ps.setString(2, runId);
            ps.setInt(3, total - maxEventsPerRun);
            ps.executeUpdate();
        }
    }

    private RunRecord recordFromRow(ResultSet rs) throws SQLException {
        return new RunRecord(
                rs.getString("run_id"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("session_id"),
                Instant.parse(rs.getString("created_at")));
    }
}
