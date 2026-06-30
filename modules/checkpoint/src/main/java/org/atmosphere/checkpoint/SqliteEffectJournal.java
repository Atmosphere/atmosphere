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

import org.atmosphere.ai.resume.EffectJournal;
import org.atmosphere.ai.resume.EffectKind;
import org.atmosphere.ai.resume.EffectRecord;
import org.atmosphere.ai.resume.EffectStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Crash-durable {@link EffectJournal} backed by an embedded SQLite database — the
 * default deterministic-replay backend. Deliberately a <em>separate</em> store
 * from {@link SqliteCheckpointStore} (its own db file and tables): the checkpoint
 * store's global oldest-first prune would delete mid-history, it has no monotonic
 * sequence column, and its {@code INSERT OR REPLACE} upsert has no compare-and-set
 * lease — none of which an effect history can tolerate.
 *
 * <h2>Schema</h2>
 *
 * <ul>
 *   <li>{@code effect_journal} — the append-only history; {@code PRIMARY KEY
 *       (run_id, seq)} with a monotonic per-run {@code seq} as the fold order and
 *       {@code UNIQUE(run_id, idempotency_key)} for the two-phase memo.</li>
 *   <li>{@code run_meta} — per-run status + creation time; retention evicts only
 *       <em>terminal</em> runs, oldest first, never an in-flight run.</li>
 *   <li>{@code run_lease} — the single-writer lease, claimed via an atomic
 *       conditional UPSERT so a rolling redeploy cannot double-drive a run.</li>
 * </ul>
 *
 * <h2>Ownership (Correctness Invariant #1)</h2>
 *
 * The journal opens and owns its own {@link Connection}; {@link #close()} closes
 * it. It never touches a connection it did not create.
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * {@link #appendPending} rejects past {@link #maxEffectsPerRun()} (failing the
 * run, never dropping recorded effects); run retention is bounded by
 * {@code maxRuns} over terminal runs only.
 *
 * @since 4.0
 */
public final class SqliteEffectJournal implements EffectJournal, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SqliteEffectJournal.class);

    /** Default cap on retained runs (terminal runs evicted oldest-first past this). */
    public static final int DEFAULT_MAX_RUNS = 10_000;

    /** Default hard per-run effect cap, matching {@code durable-runs.max-effects-per-run}. */
    public static final int DEFAULT_MAX_EFFECTS_PER_RUN = 2_000;

    private static final String RUN_META_RUNNING = "RUNNING";

    private final Connection connection;
    private final int maxRuns;
    private final int maxEffectsPerRun;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    /** Open at the given file with default bounds and the system clock. */
    public SqliteEffectJournal(Path dbPath) {
        this(dbPath, DEFAULT_MAX_RUNS, DEFAULT_MAX_EFFECTS_PER_RUN, Clock.systemUTC());
    }

    /** Open at the given file with explicit bounds and the system clock. */
    public SqliteEffectJournal(Path dbPath, int maxRuns, int maxEffectsPerRun) {
        this(dbPath, maxRuns, maxEffectsPerRun, Clock.systemUTC());
    }

    /** Test/diagnostic constructor with an injectable clock for lease-expiry control. */
    public SqliteEffectJournal(Path dbPath, int maxRuns, int maxEffectsPerRun, Clock clock) {
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be > 0, got " + maxRuns);
        }
        if (maxEffectsPerRun <= 0) {
            throw new IllegalArgumentException("maxEffectsPerRun must be > 0, got " + maxEffectsPerRun);
        }
        this.maxRuns = maxRuns;
        this.maxEffectsPerRun = maxEffectsPerRun;
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            this.connection = DriverManager.getConnection(toJdbcUrl(dbPath));
            connection.setAutoCommit(true);
            createSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite effect journal: " + dbPath, e);
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
                CREATE TABLE IF NOT EXISTS effect_journal (
                    run_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    status TEXT NOT NULL,
                    request_digest TEXT,
                    result_payload TEXT,
                    recorded_at TEXT NOT NULL,
                    PRIMARY KEY (run_id, seq),
                    UNIQUE (run_id, idempotency_key)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS run_meta (
                    run_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS run_lease (
                    run_id TEXT PRIMARY KEY,
                    owner TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_run_meta_status_created "
                    + "ON run_meta(status, created_at)");
        }
    }

    @Override
    public long appendPending(String runId, EffectKind kind,
                              String idempotencyKey, String requestDigest) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        lock.lock();
        try {
            boolean newRun = ensureRunMeta(runId);

            // Idempotent re-append: an existing key returns its recorded seq.
            var existing = seqForKey(runId, idempotencyKey);
            if (existing >= 0) {
                return existing;
            }
            if (effectCount(runId) >= maxEffectsPerRun) {
                throw new RejectedExecutionException("Effect cap exceeded for run " + runId
                        + " (maxEffectsPerRun=" + maxEffectsPerRun + "); failing the run rather "
                        + "than dropping recorded effects");
            }
            long seq = nextSeq(runId);
            try (var ps = connection.prepareStatement("INSERT INTO effect_journal "
                    + "(run_id, seq, kind, idempotency_key, status, request_digest, "
                    + "result_payload, recorded_at) VALUES (?, ?, ?, ?, 'PENDING', ?, NULL, ?)")) {
                ps.setString(1, runId);
                ps.setLong(2, seq);
                ps.setString(3, kind.name());
                ps.setString(4, idempotencyKey);
                ps.setString(5, requestDigest);
                ps.setString(6, clock.instant().toString());
                ps.executeUpdate();
            }
            if (newRun) {
                evictOldestTerminalIfOverCapacity();
            }
            return seq;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append effect for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void commit(String runId, String idempotencyKey, String resultPayload) {
        lock.lock();
        try {
            int updated = updateStatus(runId, idempotencyKey, EffectStatus.COMMITTED, resultPayload);
            if (updated == 0) {
                throw new IllegalStateException("commit without appendPending for key "
                        + idempotencyKey + " in run " + runId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to commit effect for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markFailed(String runId, String idempotencyKey, String reason) {
        lock.lock();
        try {
            int updated = updateStatus(runId, idempotencyKey, EffectStatus.FAILED, reason);
            if (updated == 0) {
                // Best-effort: never mask the original failure with a journal error.
                logger.trace("markFailed without appendPending for key {} in run {}",
                        idempotencyKey, runId);
            }
        } catch (SQLException e) {
            // Same rationale: a journal error here must not replace the real cause.
            logger.warn("Failed to mark effect FAILED for run {} (key {})", runId, idempotencyKey, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<EffectRecord> lookupCommitted(String runId, String idempotencyKey) {
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT * FROM effect_journal "
                + "WHERE run_id = ? AND idempotency_key = ? AND status = 'COMMITTED'")) {
            ps.setString(1, runId);
            ps.setString(2, idempotencyKey);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to look up effect for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<EffectRecord> fold(String runId) {
        lock.lock();
        try (var ps = connection.prepareStatement(
                "SELECT * FROM effect_journal WHERE run_id = ? ORDER BY seq ASC")) {
            ps.setString(1, runId);
            var out = new ArrayList<EffectRecord>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(fromRow(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fold run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean claimLease(String runId, String owner, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ttl, "ttl");
        lock.lock();
        try {
            long now = clock.millis();
            long expires = now + ttl.toMillis();
            // Atomic conditional UPSERT: take the lease only if it is unheld,
            // expired, or already ours. The DO UPDATE WHERE clause is the
            // compare-and-set the CheckpointStore upsert cannot express.
            try (var ps = connection.prepareStatement("INSERT INTO run_lease "
                    + "(run_id, owner, expires_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT(run_id) DO UPDATE SET owner = ?, expires_at = ? "
                    + "WHERE expires_at <= ? OR owner = ?")) {
                ps.setString(1, runId);
                ps.setString(2, owner);
                ps.setLong(3, expires);
                ps.setString(4, owner);
                ps.setLong(5, expires);
                ps.setLong(6, now);
                ps.setString(7, owner);
                ps.executeUpdate();
            }
            // Whoever now owns the row won; read it back to report truthfully.
            try (var ps = connection.prepareStatement(
                    "SELECT owner FROM run_lease WHERE run_id = ?")) {
                ps.setString(1, runId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() && owner.equals(rs.getString("owner"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim lease for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void releaseLease(String runId, String owner) {
        lock.lock();
        try (var ps = connection.prepareStatement(
                "DELETE FROM run_lease WHERE run_id = ? AND owner = ?")) {
            ps.setString(1, runId);
            ps.setString(2, owner);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to release lease for run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markTerminal(String runId, EffectStatus terminal) {
        if (terminal == EffectStatus.PENDING) {
            throw new IllegalArgumentException("terminal status must be COMMITTED or FAILED");
        }
        lock.lock();
        try {
            try (var ps = connection.prepareStatement(
                    "UPDATE run_meta SET status = ? WHERE run_id = ?")) {
                ps.setString(1, terminal.name());
                ps.setString(2, runId);
                ps.executeUpdate();
            }
            evictOldestTerminalIfOverCapacity();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark run terminal " + runId, e);
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
            throw new IllegalStateException("Failed to remove run " + runId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public int maxEffectsPerRun() {
        return maxEffectsPerRun;
    }

    /** Visible for tests / admin: number of runs currently retained. */
    public int runCount() {
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM run_meta");
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
            logger.warn("Error closing SQLite effect journal connection", e);
        } finally {
            lock.unlock();
        }
    }

    // --- helpers (all called under the lock) ---------------------------------

    private boolean ensureRunMeta(String runId) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT OR IGNORE INTO run_meta "
                + "(run_id, status, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, runId);
            ps.setString(2, RUN_META_RUNNING);
            ps.setLong(3, clock.millis());
            return ps.executeUpdate() > 0;
        }
    }

    private long seqForKey(String runId, String idempotencyKey) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT seq FROM effect_journal WHERE run_id = ? AND idempotency_key = ?")) {
            ps.setString(1, runId);
            ps.setString(2, idempotencyKey);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("seq") : -1L;
            }
        }
    }

    private int effectCount(String runId) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM effect_journal WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private long nextSeq(String runId) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), -1) + 1 FROM effect_journal WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private int updateStatus(String runId, String idempotencyKey,
                             EffectStatus status, String payload) throws SQLException {
        try (var ps = connection.prepareStatement("UPDATE effect_journal "
                + "SET status = ?, result_payload = ? WHERE run_id = ? AND idempotency_key = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, payload);
            ps.setString(3, runId);
            ps.setString(4, idempotencyKey);
            return ps.executeUpdate();
        }
    }

    private void deleteRun(String runId) throws SQLException {
        try (var ps = connection.prepareStatement(
                "DELETE FROM effect_journal WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
        try (var ps = connection.prepareStatement("DELETE FROM run_meta WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
        try (var ps = connection.prepareStatement("DELETE FROM run_lease WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
    }

    private void evictOldestTerminalIfOverCapacity() throws SQLException {
        int total;
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM run_meta");
             var rs = ps.executeQuery()) {
            total = rs.next() ? rs.getInt(1) : 0;
        }
        if (total <= maxRuns) {
            return;
        }
        // Evict only TERMINAL runs, oldest first — a non-terminal (in-flight)
        // run's history is never evicted, so a resume anchor cannot be lost.
        var victims = new ArrayList<String>();
        try (var ps = connection.prepareStatement("SELECT run_id FROM run_meta "
                + "WHERE status IN ('COMMITTED', 'FAILED') ORDER BY created_at ASC LIMIT ?")) {
            ps.setInt(1, total - maxRuns);
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

    private EffectRecord fromRow(ResultSet rs) throws SQLException {
        return new EffectRecord(
                rs.getString("run_id"),
                rs.getLong("seq"),
                EffectKind.valueOf(rs.getString("kind")),
                rs.getString("idempotency_key"),
                EffectStatus.valueOf(rs.getString("status")),
                rs.getString("request_digest"),
                rs.getString("result_payload"),
                Instant.parse(rs.getString("recorded_at")));
    }
}
