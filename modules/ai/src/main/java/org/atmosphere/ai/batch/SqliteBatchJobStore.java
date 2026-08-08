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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable {@link BatchJobStore} backed by an embedded SQLite database, so
 * batch jobs and their per-item results survive JVM restart. Mirrors the
 * repo's SQLite store conventions ({@code SqliteEvalRunStore},
 * {@code SqliteEffectJournal}): the store opens and owns its own
 * {@link Connection} (Correctness Invariant #1 — {@link #close()} closes only
 * what it created), the schema is version-stamped through the shared
 * {@code atmosphere_schema_version} migration framework (fail-closed on a
 * newer-than-code database), and retention is bounded: past
 * {@code retainedTerminalJobs} the oldest terminal jobs and their items are
 * evicted (Invariant #3).
 */
public final class SqliteBatchJobStore implements BatchJobStore {

    private static final Logger logger = LoggerFactory.getLogger(SqliteBatchJobStore.class);

    private final Connection connection;
    private final int retainedTerminalJobs;
    private final ReentrantLock lock = new ReentrantLock();

    public SqliteBatchJobStore(Path dbPath, int retainedTerminalJobs) {
        if (retainedTerminalJobs <= 0) {
            throw new IllegalArgumentException("retainedTerminalJobs must be > 0");
        }
        this.retainedTerminalJobs = retainedTerminalJobs;
        this.connection = open(dbPath);
        try {
            createSchema();
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException(
                    "Failed to initialize SQLite batch job store: " + dbPath, e);
        } catch (RuntimeException e) {
            // A schema-version refusal propagates as-is, but the connection
            // this constructor opened must still be closed (Invariant #2).
            closeQuietly(connection);
            throw e;
        }
    }

    private static Connection open(Path dbPath) {
        var abs = dbPath.toAbsolutePath();
        var parent = abs.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create directory: " + parent, e);
            }
        }
        try {
            var connection = DriverManager.getConnection("jdbc:sqlite:" + abs);
            connection.setAutoCommit(true);
            try (var stmt = connection.createStatement()) {
                // The batch store may share a database file with other
                // Atmosphere SQLite stores; wait briefly on a writer instead
                // of failing with SQLITE_BUSY.
                stmt.execute("PRAGMA busy_timeout = 5000");
            }
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite batch job store: " + abs, e);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            logger.warn("Failed to close SQLite connection: {}", e.toString());
        }
    }

    private void createSchema() throws SQLException {
        SchemaMigrations.migrate(connection, "ai_batch_jobs", List.of(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ai_batch_jobs (
                        id TEXT PRIMARY KEY,
                        agent TEXT NOT NULL,
                        submitter TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_millis INTEGER NOT NULL,
                        updated_millis INTEGER NOT NULL,
                        total_items INTEGER NOT NULL,
                        succeeded_items INTEGER NOT NULL,
                        failed_items INTEGER NOT NULL,
                        cancelled_items INTEGER NOT NULL,
                        error TEXT NOT NULL
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ai_batch_items (
                        job_id TEXT NOT NULL,
                        item_index INTEGER NOT NULL,
                        custom_id TEXT NOT NULL,
                        input TEXT NOT NULL,
                        status TEXT NOT NULL,
                        output TEXT NOT NULL,
                        error TEXT NOT NULL,
                        PRIMARY KEY (job_id, item_index)
                    )""");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ai_batch_jobs_status_created "
                        + "ON ai_batch_jobs(status, created_millis)");
            }
        }));
    }

    @Override
    public void createJob(BatchJob job, List<BatchItem> items) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(items, "items");
        lock.lock();
        try {
            if (exists(job.id())) {
                throw new IllegalStateException("Batch job id " + job.id() + " already exists");
            }
            // One transaction for the job row plus its item rows — a crash
            // must never leave a job without its items (Invariant #2).
            connection.setAutoCommit(false);
            try {
                try (var ps = connection.prepareStatement("INSERT INTO ai_batch_jobs (id, "
                        + "agent, submitter, status, created_millis, updated_millis, total_items, "
                        + "succeeded_items, failed_items, cancelled_items, error) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, job.id());
                    ps.setString(2, job.agent());
                    ps.setString(3, job.submitter());
                    ps.setString(4, job.status().wire());
                    ps.setLong(5, job.createdAt().toEpochMilli());
                    ps.setLong(6, job.updatedAt().toEpochMilli());
                    ps.setInt(7, job.totalItems());
                    ps.setInt(8, job.succeededItems());
                    ps.setInt(9, job.failedItems());
                    ps.setInt(10, job.cancelledItems());
                    ps.setString(11, job.error());
                    ps.executeUpdate();
                }
                try (var ps = connection.prepareStatement("INSERT INTO ai_batch_items (job_id, "
                        + "item_index, custom_id, input, status, output, error) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (var item : items) {
                        ps.setString(1, job.id());
                        ps.setInt(2, item.index());
                        ps.setString(3, item.customId());
                        ps.setString(4, item.input());
                        ps.setString(5, item.status().wire());
                        ps.setString(6, item.output());
                        ps.setString(7, item.error());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
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
            throw new IllegalStateException("Failed to create batch job " + job.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<BatchJob> job(String id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT * FROM ai_batch_jobs WHERE id = ?")) {
            ps.setString(1, id);
            var rows = readJobs(ps);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load batch job " + id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BatchJob> jobs(int limit) {
        lock.lock();
        try (var ps = connection.prepareStatement(
                "SELECT * FROM ai_batch_jobs ORDER BY created_millis DESC, id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(0, limit));
            return readJobs(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list batch jobs", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BatchItem> items(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        lock.lock();
        try (var ps = connection.prepareStatement(
                "SELECT * FROM ai_batch_items WHERE job_id = ? ORDER BY item_index ASC")) {
            ps.setString(1, jobId);
            var result = new ArrayList<BatchItem>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BatchItem(
                            rs.getInt("item_index"),
                            rs.getString("custom_id"),
                            rs.getString("input"),
                            BatchItem.Status.fromWire(rs.getString("status")),
                            rs.getString("output"),
                            rs.getString("error")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list items of batch job " + jobId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean markRunning(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        lock.lock();
        try (var ps = connection.prepareStatement("UPDATE ai_batch_jobs SET status = ?, "
                + "updated_millis = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, BatchJob.Status.RUNNING.wire());
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.setString(3, jobId);
            ps.setString(4, BatchJob.Status.QUEUED.wire());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark batch job running " + jobId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean completeItem(String jobId, int index, BatchItem.Status status,
                                String output, String error) {
        Objects.requireNonNull(jobId, "jobId");
        if (!status.terminal()) {
            throw new IllegalArgumentException("completeItem requires a terminal status");
        }
        lock.lock();
        try {
            int updated;
            try (var ps = connection.prepareStatement("UPDATE ai_batch_items SET status = ?, "
                    + "output = ?, error = ? WHERE job_id = ? AND item_index = ? AND status = ?")) {
                ps.setString(1, status.wire());
                ps.setString(2, output != null ? output : "");
                ps.setString(3, error != null ? error : "");
                ps.setString(4, jobId);
                ps.setInt(5, index);
                ps.setString(6, BatchItem.Status.PENDING.wire());
                updated = ps.executeUpdate();
            }
            if (updated == 1) {
                refreshCounts(jobId);
            }
            return updated == 1;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to complete item " + index + " of batch job " + jobId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean finishJob(String jobId, BatchJob.Status status, String error) {
        Objects.requireNonNull(jobId, "jobId");
        if (!status.terminal()) {
            throw new IllegalArgumentException("finishJob requires a terminal status");
        }
        lock.lock();
        try {
            return finishLocked(jobId, status, error != null ? error : "");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to finish batch job " + jobId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int countOpen() {
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM ai_batch_jobs "
                + "WHERE status IN (?, ?)")) {
            ps.setString(1, BatchJob.Status.QUEUED.wire());
            ps.setString(2, BatchJob.Status.RUNNING.wire());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count open batch jobs", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int failInFlight(String error) {
        lock.lock();
        try {
            var open = new ArrayList<String>();
            try (var ps = connection.prepareStatement(
                    "SELECT id FROM ai_batch_jobs WHERE status IN (?, ?)")) {
                ps.setString(1, BatchJob.Status.QUEUED.wire());
                ps.setString(2, BatchJob.Status.RUNNING.wire());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        open.add(rs.getString(1));
                    }
                }
            }
            int transitioned = 0;
            for (var id : open) {
                if (finishLocked(id, BatchJob.Status.FAILED, error != null ? error : "")) {
                    transitioned++;
                }
            }
            return transitioned;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fail in-flight batch jobs", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public void close() {
        closeQuietly(connection);
    }

    // ── Internals (all callers hold the lock) ──────────────────────────────

    private boolean finishLocked(String jobId, BatchJob.Status status, String error)
            throws SQLException {
        int updated;
        try (var ps = connection.prepareStatement("UPDATE ai_batch_jobs SET status = ?, "
                + "error = ?, updated_millis = ? WHERE id = ? AND status IN (?, ?)")) {
            ps.setString(1, status.wire());
            ps.setString(2, error);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.setString(4, jobId);
            ps.setString(5, BatchJob.Status.QUEUED.wire());
            ps.setString(6, BatchJob.Status.RUNNING.wire());
            updated = ps.executeUpdate();
        }
        if (updated != 1) {
            return false;
        }
        sweepPending(jobId, status, error);
        refreshCounts(jobId);
        evictTerminalOverCap();
        return true;
    }

    /** Sweep still-pending items to the terminal status implied by the job's. */
    private void sweepPending(String jobId, BatchJob.Status jobStatus, String error)
            throws SQLException {
        var itemStatus = jobStatus == BatchJob.Status.CANCELLED
                ? BatchItem.Status.CANCELLED : BatchItem.Status.FAILED;
        var itemError = jobStatus == BatchJob.Status.CANCELLED
                ? "cancelled"
                : (error.isBlank() ? "job finished before this item completed" : error);
        try (var ps = connection.prepareStatement("UPDATE ai_batch_items SET status = ?, "
                + "error = ? WHERE job_id = ? AND status = ?")) {
            ps.setString(1, itemStatus.wire());
            ps.setString(2, itemError);
            ps.setString(3, jobId);
            ps.setString(4, BatchItem.Status.PENDING.wire());
            ps.executeUpdate();
        }
    }

    /** Recompute the job's item counters from the items table. */
    private void refreshCounts(String jobId) throws SQLException {
        try (var ps = connection.prepareStatement("UPDATE ai_batch_jobs SET "
                + "succeeded_items = (SELECT COUNT(*) FROM ai_batch_items "
                + "  WHERE job_id = ai_batch_jobs.id AND status = 'succeeded'), "
                + "failed_items = (SELECT COUNT(*) FROM ai_batch_items "
                + "  WHERE job_id = ai_batch_jobs.id AND status = 'failed'), "
                + "cancelled_items = (SELECT COUNT(*) FROM ai_batch_items "
                + "  WHERE job_id = ai_batch_jobs.id AND status = 'cancelled'), "
                + "updated_millis = ? WHERE id = ?")) {
            ps.setLong(1, Instant.now().toEpochMilli());
            ps.setString(2, jobId);
            ps.executeUpdate();
        }
    }

    private void evictTerminalOverCap() throws SQLException {
        int terminal;
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM ai_batch_jobs "
                + "WHERE status NOT IN (?, ?)")) {
            ps.setString(1, BatchJob.Status.QUEUED.wire());
            ps.setString(2, BatchJob.Status.RUNNING.wire());
            try (var rs = ps.executeQuery()) {
                terminal = rs.next() ? rs.getInt(1) : 0;
            }
        }
        var excess = terminal - retainedTerminalJobs;
        if (excess <= 0) {
            return;
        }
        var evicted = new ArrayList<String>(excess);
        try (var ps = connection.prepareStatement("SELECT id FROM ai_batch_jobs "
                + "WHERE status NOT IN (?, ?) ORDER BY updated_millis ASC, id ASC LIMIT ?")) {
            ps.setString(1, BatchJob.Status.QUEUED.wire());
            ps.setString(2, BatchJob.Status.RUNNING.wire());
            ps.setInt(3, excess);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    evicted.add(rs.getString(1));
                }
            }
        }
        try (var items = connection.prepareStatement(
                     "DELETE FROM ai_batch_items WHERE job_id = ?");
             var jobsDelete = connection.prepareStatement(
                     "DELETE FROM ai_batch_jobs WHERE id = ?")) {
            for (var id : evicted) {
                items.setString(1, id);
                items.executeUpdate();
                jobsDelete.setString(1, id);
                jobsDelete.executeUpdate();
            }
        }
    }

    private boolean exists(String id) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT 1 FROM ai_batch_jobs WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static List<BatchJob> readJobs(PreparedStatement ps) throws SQLException {
        var result = new ArrayList<BatchJob>();
        try (var rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(readJob(rs));
            }
        }
        return List.copyOf(result);
    }

    private static BatchJob readJob(ResultSet rs) throws SQLException {
        return new BatchJob(
                rs.getString("id"),
                rs.getString("agent"),
                rs.getString("submitter"),
                BatchJob.Status.fromWire(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("created_millis")),
                Instant.ofEpochMilli(rs.getLong("updated_millis")),
                rs.getInt("total_items"),
                rs.getInt("succeeded_items"),
                rs.getInt("failed_items"),
                rs.getInt("cancelled_items"),
                rs.getString("error"));
    }
}
