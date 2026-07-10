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

import org.atmosphere.ai.tape.TapeQuery;
import org.atmosphere.ai.tape.TapeRun;
import org.atmosphere.ai.tape.TapeStatus;
import org.atmosphere.ai.tape.TapeStep;
import org.atmosphere.ai.tape.TapeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Crash-durable {@link TapeStore} backed by an embedded SQLite database — the
 * default durable backend for the session tape. Deliberately a <em>separate</em>
 * store from {@link SqliteEffectJournal} (its own db file and tables): the tape
 * is a typed step log that survives run completion, not a compute re-drive
 * journal, and its step cap is stop-record + truncated flag — observability,
 * never the effect journal's fail-the-run.
 *
 * <h2>Schema</h2>
 *
 * <ul>
 *   <li>{@code tape_run} — one row per taped run; {@code status} is write-once
 *       terminal and all timestamps are INTEGER epoch millis (the
 *       {@code run_meta.created_at} precedent — avoids the ISO-8601 TEXT sort
 *       trap).</li>
 *   <li>{@code tape_step} — the append-only step history; {@code PRIMARY KEY
 *       (run_id, seq)} with a monotonic per-run {@code seq}. A re-opened run
 *       (crash-resume re-drive) continues at {@code MAX(seq)+1}: the resumed
 *       writer restarts its numbering at zero and this store re-bases it so
 *       the history stays append-only.</li>
 *   <li>{@code idx_tape_run_status_started} — serves the retention sweep and
 *       status queries (the {@code idx_run_meta_status_created} precedent).</li>
 * </ul>
 *
 * <h2>Ownership (Correctness Invariant #1)</h2>
 *
 * The store opens and owns its own {@link Connection}; {@link #close()} closes
 * it. It never touches a connection it did not create.
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * Bounded on both axes: at most {@link #maxRuns()} retained runs — evicting the
 * oldest <em>terminal</em> run only, never an in-flight one — and at most
 * {@link #maxStepsPerRun()} steps per run, beyond which appends stop recording
 * and flag the run {@link TapeRun#truncated() truncated} (never a throw — the
 * tape must never fail a healthy stream over its own cap).
 *
 * <p>Each append batch runs in an explicit transaction (autocommit off, commit
 * or rollback, autocommit restored) so a mid-batch failure never leaves partial
 * rows behind.</p>
 *
 * @since 4.0
 */
public final class SqliteTapeStore implements TapeStore {

    private static final Logger logger = LoggerFactory.getLogger(SqliteTapeStore.class);

    /** Default cap on retained runs (terminal runs evicted oldest-first past this). */
    public static final int DEFAULT_MAX_RUNS = 10_000;

    /** Default per-run step cap, matching {@code atmosphere.ai.tape.max-steps-per-run}. */
    public static final int DEFAULT_MAX_STEPS_PER_RUN = 5_000;

    private final Connection connection;
    private final int maxRuns;
    private final int maxStepsPerRun;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Per-run seq re-base, guarded by {@link #lock}: {@code 0} for runs this
     * instance began fresh, {@code MAX(seq)+1} for re-opened runs so a resumed
     * writer's restarted numbering lands after the persisted history. Bounded:
     * entries are removed on terminal, eviction, and {@link #removeRun}.
     */
    private final Map<String, Long> seqBase = new HashMap<>();

    /** Open at the given file with default bounds. */
    public SqliteTapeStore(Path dbPath) {
        this(dbPath, DEFAULT_MAX_RUNS, DEFAULT_MAX_STEPS_PER_RUN);
    }

    /** Open at the given file with explicit bounds. */
    public SqliteTapeStore(Path dbPath, int maxRuns, int maxStepsPerRun) {
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be > 0, got " + maxRuns);
        }
        if (maxStepsPerRun <= 0) {
            throw new IllegalArgumentException("maxStepsPerRun must be > 0, got " + maxStepsPerRun);
        }
        this.maxRuns = maxRuns;
        this.maxStepsPerRun = maxStepsPerRun;
        try {
            this.connection = DriverManager.getConnection(toJdbcUrl(dbPath));
            connection.setAutoCommit(true);
            createSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite tape store: " + dbPath, e);
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
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tape_run (
                    run_id TEXT PRIMARY KEY,
                    tape_id TEXT,
                    session_id TEXT,
                    resource_uuid TEXT,
                    user_id TEXT,
                    endpoint TEXT,
                    model TEXT,
                    runtime TEXT,
                    status TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    step_count INTEGER NOT NULL DEFAULT 0,
                    dropped_steps INTEGER NOT NULL DEFAULT 0,
                    truncated INTEGER NOT NULL DEFAULT 0,
                    parent_run_id TEXT
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tape_step (
                    run_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    ts INTEGER NOT NULL,
                    PRIMARY KEY (run_id, seq)
                )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tape_run_status_started "
                    + "ON tape_run(status, started_at)");
        }
    }

    @Override
    public void begin(TapeRun run) {
        Objects.requireNonNull(run, "run");
        lock.lock();
        try {
            var existing = runStatus(run.runId());
            if (existing == null) {
                // step_count starts at 0 — the column tracks this store's own
                // rows (runtime truth), not the caller's view.
                try (var ps = connection.prepareStatement("INSERT INTO tape_run "
                        + "(run_id, tape_id, session_id, resource_uuid, user_id, endpoint, "
                        + "model, runtime, status, started_at, ended_at, step_count, "
                        + "dropped_steps, truncated, parent_run_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)")) {
                    ps.setString(1, run.runId());
                    ps.setString(2, run.tapeId());
                    ps.setString(3, run.sessionId());
                    ps.setString(4, run.resourceUuid());
                    ps.setString(5, run.userId());
                    ps.setString(6, run.endpoint());
                    ps.setString(7, run.model());
                    ps.setString(8, run.runtimeName());
                    ps.setString(9, run.status().name());
                    ps.setLong(10, run.startedAt());
                    ps.setObject(11, run.endedAt());
                    ps.setLong(12, run.droppedSteps());
                    ps.setInt(13, run.truncated() ? 1 : 0);
                    ps.setString(14, run.parentRunId());
                    ps.executeUpdate();
                }
                seqBase.put(run.runId(), 0L);
                evictOldestTerminalIfOverCapacity();
            } else {
                // Idempotent upsert (crash-resume re-begin): refresh identity
                // metadata only — never regress status, steps, or counters.
                try (var ps = connection.prepareStatement("UPDATE tape_run SET "
                        + "tape_id = COALESCE(?, tape_id), "
                        + "session_id = COALESCE(?, session_id), "
                        + "resource_uuid = COALESCE(?, resource_uuid), "
                        + "user_id = COALESCE(?, user_id), "
                        + "endpoint = COALESCE(?, endpoint), "
                        + "model = COALESCE(?, model), "
                        + "runtime = COALESCE(?, runtime) WHERE run_id = ?")) {
                    ps.setString(1, run.tapeId());
                    ps.setString(2, run.sessionId());
                    ps.setString(3, run.resourceUuid());
                    ps.setString(4, run.userId());
                    ps.setString(5, run.endpoint());
                    ps.setString(6, run.model());
                    ps.setString(7, run.runtimeName());
                    ps.setString(8, run.runId());
                    ps.executeUpdate();
                }
                if (!seqBase.containsKey(run.runId())) {
                    // A re-opened run continues at MAX(seq)+1: the resumed
                    // writer restarts its numbering at 0 and the re-base keeps
                    // the (run_id, seq) history append-only.
                    seqBase.put(run.runId(), nextSeq(run.runId()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to begin tape run " + run.runId(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void append(String runId, List<TapeStep> steps) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            var status = runStatus(runId);
            if (status == null) {
                logger.trace("append for unknown tape run {} — ignored", runId);
                return;
            }
            if (status.terminal()) {
                // Reject-or-ignore contract: never insert after markTerminal.
                return;
            }
            long base = seqBaseFor(runId);
            int existing = stepCount(runId);
            connection.setAutoCommit(false);
            try {
                int inserted = 0;
                boolean capped = false;
                try (var ps = connection.prepareStatement("INSERT INTO tape_step "
                        + "(run_id, seq, kind, payload, ts) VALUES (?, ?, ?, ?, ?)")) {
                    for (var step : steps) {
                        if (existing + inserted >= maxStepsPerRun) {
                            // Stop-record + truncated flag: the tape must never
                            // fail a healthy stream over its own cap.
                            capped = true;
                            break;
                        }
                        ps.setString(1, runId);
                        ps.setLong(2, base + step.seq());
                        ps.setString(3, step.kind());
                        ps.setString(4, step.payload());
                        ps.setLong(5, step.ts());
                        ps.executeUpdate();
                        inserted++;
                    }
                }
                if (inserted > 0) {
                    try (var ps = connection.prepareStatement(
                            "UPDATE tape_run SET step_count = step_count + ? WHERE run_id = ?")) {
                        ps.setInt(1, inserted);
                        ps.setString(2, runId);
                        ps.executeUpdate();
                    }
                }
                if (capped) {
                    try (var ps = connection.prepareStatement(
                            "UPDATE tape_run SET truncated = 1 WHERE run_id = ?")) {
                        ps.setString(1, runId);
                        ps.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append tape steps for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markTerminal(String runId, TapeStatus status, Counters counters) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(counters, "counters");
        if (!status.terminal()) {
            throw new IllegalArgumentException("terminal status required, got " + status);
        }
        lock.lock();
        try {
            var current = runStatus(runId);
            if (current == null) {
                logger.trace("markTerminal for unknown tape run {} — ignored", runId);
                return;
            }
            if (current.terminal()) {
                // Write-once: first terminal wins, never a status flip.
                return;
            }
            // The AND status = 'OPEN' guard is belt-and-braces for write-once;
            // truncated ORs the writer's cap flag with the store-local one.
            try (var ps = connection.prepareStatement("UPDATE tape_run SET status = ?, "
                    + "ended_at = ?, dropped_steps = ?, truncated = MAX(truncated, ?) "
                    + "WHERE run_id = ? AND status = ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, counters.droppedSteps());
                ps.setInt(4, counters.truncated() ? 1 : 0);
                ps.setString(5, runId);
                ps.setString(6, TapeStatus.OPEN.name());
                ps.executeUpdate();
            }
            seqBase.remove(runId);
            evictOldestTerminalIfOverCapacity();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark tape run terminal " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TapeRun> listRuns(TapeQuery query) {
        Objects.requireNonNull(query, "query");
        var sql = new StringBuilder("SELECT * FROM tape_run");
        var params = new ArrayList<Object>();
        if (query.tapeId() != null) {
            sql.append(" WHERE tape_id = ?");
            params.add(query.tapeId());
        }
        if (query.status() != null) {
            sql.append(params.isEmpty() ? " WHERE " : " AND ").append("status = ?");
            params.add(query.status().name());
        }
        sql.append(" ORDER BY started_at DESC, run_id ASC");
        if (query.limit() > 0) {
            sql.append(" LIMIT ?");
            params.add(query.limit());
        }
        lock.lock();
        try (var ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            var out = new ArrayList<TapeRun>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(runFromRow(rs));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list tape runs", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TapeStep> readSteps(String runId, long fromSeq, int max) {
        var sql = "SELECT run_id, seq, kind, payload, ts FROM tape_step "
                + "WHERE run_id = ? AND seq >= ? ORDER BY seq ASC" + (max > 0 ? " LIMIT ?" : "");
        lock.lock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setLong(2, fromSeq);
            if (max > 0) {
                ps.setInt(3, max);
            }
            var out = new ArrayList<TapeStep>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TapeStep(rs.getString("run_id"), rs.getLong("seq"),
                            rs.getString("kind"), rs.getString("payload"), rs.getLong("ts")));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read tape steps for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> fork(String runId) {
        lock.lock();
        try {
            var source = findRunRow(runId);
            if (source == null) {
                return Optional.empty();
            }
            var forkId = "tape-" + UUID.randomUUID();
            long copiedCount = stepCount(runId);
            long forkNextSeq = nextSeq(runId);
            connection.setAutoCommit(false);
            try {
                try (var ps = connection.prepareStatement("INSERT INTO tape_run "
                        + "(run_id, tape_id, session_id, resource_uuid, user_id, endpoint, "
                        + "model, runtime, status, started_at, ended_at, step_count, "
                        + "dropped_steps, truncated, parent_run_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0, 0, ?)")) {
                    ps.setString(1, forkId);
                    ps.setString(2, source.tapeId());
                    ps.setString(3, source.sessionId());
                    ps.setString(4, source.resourceUuid());
                    ps.setString(5, source.userId());
                    ps.setString(6, source.endpoint());
                    ps.setString(7, source.model());
                    ps.setString(8, source.runtimeName());
                    ps.setString(9, TapeStatus.OPEN.name());
                    ps.setLong(10, System.currentTimeMillis());
                    ps.setLong(11, copiedCount);
                    ps.setString(12, runId);
                    ps.executeUpdate();
                }
                try (var ps = connection.prepareStatement("INSERT INTO tape_step "
                        + "(run_id, seq, kind, payload, ts) "
                        + "SELECT ?, seq, kind, payload, ts FROM tape_step WHERE run_id = ?")) {
                    ps.setString(1, forkId);
                    ps.setString(2, runId);
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            // Appends to the fork continue after the copied history.
            seqBase.put(forkId, forkNextSeq);
            evictOldestTerminalIfOverCapacity();
            return Optional.of(forkId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fork tape run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void removeRun(String runId) {
        lock.lock();
        try {
            deleteRun(runId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove tape run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int maxRuns() {
        return maxRuns;
    }

    @Override
    public int maxStepsPerRun() {
        return maxStepsPerRun;
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public String name() {
        return "sqlite";
    }

    /** Visible for tests / admin: number of runs currently retained. */
    public int runCount() {
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM tape_run");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count tape runs", e);
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
            logger.warn("Error closing SQLite tape store connection", e);
        } finally {
            lock.unlock();
        }
    }

    // --- helpers (all called under the lock) ---------------------------------

    private TapeStatus runStatus(String runId) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT status FROM tape_run WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? TapeStatus.valueOf(rs.getString("status")) : null;
            }
        }
    }

    private TapeRun findRunRow(String runId) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT * FROM tape_run WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? runFromRow(rs) : null;
            }
        }
    }

    private long seqBaseFor(String runId) throws SQLException {
        var cached = seqBase.get(runId);
        if (cached != null) {
            return cached;
        }
        // First touch by this instance without a begin (defensive): treat it
        // like a re-opened run and continue after the persisted history.
        long next = nextSeq(runId);
        seqBase.put(runId, next);
        return next;
    }

    private long nextSeq(String runId) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), -1) + 1 FROM tape_step WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private int stepCount(String runId) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM tape_step WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void deleteRun(String runId) throws SQLException {
        try (var ps = connection.prepareStatement("DELETE FROM tape_step WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
        try (var ps = connection.prepareStatement("DELETE FROM tape_run WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
        seqBase.remove(runId);
    }

    private void evictOldestTerminalIfOverCapacity() throws SQLException {
        int total;
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM tape_run");
             var rs = ps.executeQuery()) {
            total = rs.next() ? rs.getInt(1) : 0;
        }
        if (total <= maxRuns) {
            return;
        }
        // Evict only TERMINAL runs, oldest first — an OPEN (in-flight) run's
        // tape is never evicted out from under its writer, and stays available
        // as a crash-resume anchor (SqliteEffectJournal protects non-terminal
        // effect runs identically). The maxRuns bound still holds against
        // external input: the recorder's idle sweep marks a silent OPEN run
        // ABANDONED (→ terminal → evictable) after the idle timeout, so only a
        // process crash can leave an OPEN row behind, and those are legitimate
        // resume anchors bounded by restart count, not by request volume.
        var victims = new ArrayList<String>();
        try (var ps = connection.prepareStatement("SELECT run_id FROM tape_run "
                + "WHERE status <> ? ORDER BY started_at ASC LIMIT ?")) {
            ps.setString(1, TapeStatus.OPEN.name());
            ps.setInt(2, total - maxRuns);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    victims.add(rs.getString("run_id"));
                }
            }
        }
        for (var runId : victims) {
            deleteRun(runId);
        }
    }

    private TapeRun runFromRow(ResultSet rs) throws SQLException {
        long endedAtRaw = rs.getLong("ended_at");
        Long endedAt = rs.wasNull() ? null : endedAtRaw;
        return new TapeRun(
                rs.getString("run_id"),
                rs.getString("tape_id"),
                rs.getString("session_id"),
                rs.getString("resource_uuid"),
                rs.getString("user_id"),
                rs.getString("endpoint"),
                rs.getString("model"),
                rs.getString("runtime"),
                rs.getLong("started_at"),
                TapeStatus.valueOf(rs.getString("status")),
                endedAt,
                rs.getLong("step_count"),
                rs.getLong("dropped_steps"),
                rs.getInt("truncated") != 0,
                rs.getString("parent_run_id"));
    }
}
