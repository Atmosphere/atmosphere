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
package org.atmosphere.admin.evals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
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
 * Durable {@link EvalDatasetStore} backed by an embedded SQLite database, so a
 * curated eval dataset survives restart (the whole point of promoting
 * production traces into regression fixtures). Same conventions as
 * {@link SqliteEvalRunStore}: the store owns its {@link Connection}
 * (Correctness Invariant #1), the schema is created idempotently, and the case
 * count is bounded (Invariant #3) with oldest-first eviction past
 * {@code maxCases}, matching {@link InMemoryEvalDatasetStore} semantics.
 */
public final class SqliteEvalDatasetStore implements EvalDatasetStore, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SqliteEvalDatasetStore.class);

    /** Default retention bound, matching {@link InMemoryEvalDatasetStore}. */
    public static final int DEFAULT_MAX_CASES = 2000;

    private final Connection connection;
    private final int maxCases;
    private final ReentrantLock lock = new ReentrantLock();

    /** Open at the given file with the default retention bound. */
    public SqliteEvalDatasetStore(Path dbPath) {
        this(dbPath, DEFAULT_MAX_CASES);
    }

    public SqliteEvalDatasetStore(Path dbPath, int maxCases) {
        if (maxCases <= 0) {
            throw new IllegalArgumentException("maxCases must be > 0");
        }
        this.maxCases = maxCases;
        this.connection = SqliteEvalSupport.open(dbPath, "eval dataset store");
        try {
            createSchema();
        } catch (SQLException e) {
            SqliteEvalSupport.closeQuietly(connection, logger);
            throw new IllegalStateException(
                    "Failed to initialize SQLite eval dataset store: " + dbPath, e);
        }
    }

    private void createSchema() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS eval_dataset (
                    id TEXT PRIMARY KEY,
                    prompt TEXT NOT NULL,
                    reference_answer TEXT NOT NULL,
                    source TEXT NOT NULL,
                    tags TEXT NOT NULL,
                    captured_iso TEXT NOT NULL,
                    captured_millis INTEGER NOT NULL
                )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_eval_dataset_captured "
                    + "ON eval_dataset(captured_millis)");
        }
    }

    @Override
    public List<EvalCase> list() {
        lock.lock();
        try (var ps = connection.prepareStatement(
                "SELECT * FROM eval_dataset ORDER BY captured_millis DESC, id DESC")) {
            return readAll(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list eval dataset cases", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<EvalCase> findById(String id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT * FROM eval_dataset WHERE id = ?")) {
            ps.setString(1, id);
            var rows = readAll(ps);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load eval case " + id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public EvalCase save(EvalCase evalCase) {
        Objects.requireNonNull(evalCase, "evalCase");
        lock.lock();
        try {
            if (exists(evalCase.id())) {
                throw new IllegalStateException("EvalCase id " + evalCase.id() + " already exists");
            }
            try (var ps = connection.prepareStatement("INSERT INTO eval_dataset (id, prompt, "
                    + "reference_answer, source, tags, captured_iso, captured_millis) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, evalCase.id());
                ps.setString(2, evalCase.prompt());
                ps.setString(3, evalCase.reference());
                ps.setString(4, evalCase.source());
                ps.setString(5, SqliteEvalSupport.encodeList(evalCase.tags()));
                ps.setString(6, evalCase.capturedAt().toString());
                ps.setLong(7, evalCase.capturedAt().toEpochMilli());
                ps.executeUpdate();
            }
            evictOldestIfOverCap();
            return evalCase;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save eval case " + evalCase.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try (var ps = connection.prepareStatement("DELETE FROM eval_dataset WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete eval case " + id, e);
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
        SqliteEvalSupport.closeQuietly(connection, logger);
    }

    private boolean exists(String id) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT 1 FROM eval_dataset WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void evictOldestIfOverCap() throws SQLException {
        int count;
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM eval_dataset")) {
            try (var rs = ps.executeQuery()) {
                count = rs.next() ? rs.getInt(1) : 0;
            }
        }
        var excess = count - maxCases;
        if (excess <= 0) {
            return;
        }
        try (var ps = connection.prepareStatement("DELETE FROM eval_dataset WHERE id IN ("
                + "SELECT id FROM eval_dataset ORDER BY captured_millis ASC, id ASC LIMIT ?)")) {
            ps.setInt(1, excess);
            ps.executeUpdate();
        }
    }

    private static List<EvalCase> readAll(PreparedStatement ps) throws SQLException {
        var result = new ArrayList<EvalCase>();
        try (var rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(read(rs));
            }
        }
        return List.copyOf(result);
    }

    private static EvalCase read(ResultSet rs) throws SQLException {
        return new EvalCase(
                rs.getString("id"),
                rs.getString("prompt"),
                rs.getString("reference_answer"),
                rs.getString("source"),
                SqliteEvalSupport.decodeList(rs.getString("tags")),
                Instant.parse(rs.getString("captured_iso")));
    }
}
