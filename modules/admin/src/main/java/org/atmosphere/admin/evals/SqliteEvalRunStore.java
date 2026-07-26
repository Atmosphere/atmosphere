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
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable {@link EvalRunStore} backed by an embedded SQLite database, so eval
 * history survives JVM restart. Mirrors the checkpoint module's SQLite
 * conventions ({@code SqliteEffectJournal}): the store opens and owns its own
 * {@link Connection} (Correctness Invariant #1 — {@link #close()} closes only
 * what it created), the schema is created idempotently, and retention is
 * bounded per baseline (Invariant #3): past {@code maxRunsPerBaseline} the
 * oldest rows for that baseline are evicted, matching
 * {@link InMemoryEvalRunStore} semantics.
 */
public final class SqliteEvalRunStore implements EvalRunStore, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SqliteEvalRunStore.class);

    /** Default retention per baseline, matching {@link InMemoryEvalRunStore}. */
    public static final int DEFAULT_MAX_RUNS_PER_BASELINE = 500;

    private final Connection connection;
    private final int maxRunsPerBaseline;
    private final ReentrantLock lock = new ReentrantLock();

    /** Open at the given file with the default per-baseline retention bound. */
    public SqliteEvalRunStore(Path dbPath) {
        this(dbPath, DEFAULT_MAX_RUNS_PER_BASELINE);
    }

    public SqliteEvalRunStore(Path dbPath, int maxRunsPerBaseline) {
        if (maxRunsPerBaseline <= 0) {
            throw new IllegalArgumentException("maxRunsPerBaseline must be > 0");
        }
        this.maxRunsPerBaseline = maxRunsPerBaseline;
        this.connection = SqliteEvalSupport.open(dbPath, "eval run store");
        try {
            createSchema();
        } catch (SQLException e) {
            SqliteEvalSupport.closeQuietly(connection, logger);
            throw new IllegalStateException("Failed to initialize SQLite eval run store: " + dbPath, e);
        } catch (RuntimeException e) {
            // A schema-version refusal propagates as-is, but the connection
            // this constructor opened must still be closed (Invariant #2).
            SqliteEvalSupport.closeQuietly(connection, logger);
            throw e;
        }
    }

    private void createSchema() throws SQLException {
        SchemaMigrations.migrate(connection, "eval_runs", List.of(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS eval_runs (
                        id TEXT PRIMARY KEY,
                        baseline TEXT NOT NULL,
                        ts_iso TEXT NOT NULL,
                        ts_millis INTEGER NOT NULL,
                        agent_version TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        judge_response TEXT NOT NULL,
                        verdict INTEGER,
                        scores TEXT NOT NULL,
                        judge_model TEXT NOT NULL,
                        passed INTEGER NOT NULL,
                        notes TEXT NOT NULL
                    )""");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_eval_runs_baseline_ts "
                        + "ON eval_runs(baseline, ts_millis)");
            }
        }));
    }

    @Override
    public List<EvalRun> list() {
        lock.lock();
        try (var ps = connection.prepareStatement(
                "SELECT * FROM eval_runs ORDER BY ts_millis DESC, id DESC")) {
            return readAll(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list eval runs", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<EvalRun> listForBaseline(String baseline) {
        Objects.requireNonNull(baseline, "baseline");
        lock.lock();
        try (var ps = connection.prepareStatement(
                "SELECT * FROM eval_runs WHERE baseline = ? ORDER BY ts_millis DESC, id DESC")) {
            ps.setString(1, baseline);
            return readAll(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list eval runs for " + baseline, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<EvalRun> findById(String id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try (var ps = connection.prepareStatement("SELECT * FROM eval_runs WHERE id = ?")) {
            ps.setString(1, id);
            var rows = readAll(ps);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load eval run " + id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public EvalRun save(EvalRun run) {
        Objects.requireNonNull(run, "run");
        lock.lock();
        try {
            if (exists(run.id())) {
                throw new IllegalStateException(
                        "EvalRun id " + run.id() + " already exists; eval runs are immutable");
            }
            try (var ps = connection.prepareStatement("INSERT INTO eval_runs (id, baseline, "
                    + "ts_iso, ts_millis, agent_version, prompt, judge_response, verdict, "
                    + "scores, judge_model, passed, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, run.id());
                ps.setString(2, run.baseline());
                ps.setString(3, run.timestamp().toString());
                ps.setLong(4, run.timestamp().toEpochMilli());
                ps.setString(5, run.agentVersion());
                ps.setString(6, run.prompt());
                ps.setString(7, run.judgeResponse());
                if (run.verdict() == null) {
                    ps.setNull(8, Types.INTEGER);
                } else {
                    ps.setInt(8, run.verdict() ? 1 : 0);
                }
                ps.setString(9, SqliteEvalSupport.encodePairs(scoresAsStrings(run.scores())));
                ps.setString(10, run.judgeModel());
                ps.setInt(11, run.passed() ? 1 : 0);
                ps.setString(12, run.notes());
                ps.executeUpdate();
            }
            evictOldestForBaselineIfOverCap(run.baseline());
            return run;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save eval run " + run.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try (var ps = connection.prepareStatement("DELETE FROM eval_runs WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete eval run " + id, e);
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
        try (var ps = connection.prepareStatement("SELECT 1 FROM eval_runs WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void evictOldestForBaselineIfOverCap(String baseline) throws SQLException {
        int count;
        try (var ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM eval_runs WHERE baseline = ?")) {
            ps.setString(1, baseline);
            try (var rs = ps.executeQuery()) {
                count = rs.next() ? rs.getInt(1) : 0;
            }
        }
        var excess = count - maxRunsPerBaseline;
        if (excess <= 0) {
            return;
        }
        try (var ps = connection.prepareStatement("DELETE FROM eval_runs WHERE id IN ("
                + "SELECT id FROM eval_runs WHERE baseline = ? "
                + "ORDER BY ts_millis ASC, id ASC LIMIT ?)")) {
            ps.setString(1, baseline);
            ps.setInt(2, excess);
            ps.executeUpdate();
        }
    }

    private static List<EvalRun> readAll(PreparedStatement ps) throws SQLException {
        var result = new ArrayList<EvalRun>();
        try (var rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(read(rs));
            }
        }
        return List.copyOf(result);
    }

    private static EvalRun read(ResultSet rs) throws SQLException {
        var verdictRaw = rs.getObject("verdict");
        Boolean verdict = verdictRaw == null ? null : ((Number) verdictRaw).intValue() != 0;
        return new EvalRun(
                rs.getString("id"),
                rs.getString("baseline"),
                Instant.parse(rs.getString("ts_iso")),
                rs.getString("agent_version"),
                rs.getString("prompt"),
                rs.getString("judge_response"),
                verdict,
                scoresFromStrings(SqliteEvalSupport.decodePairs(rs.getString("scores"))),
                rs.getString("judge_model"),
                rs.getInt("passed") != 0,
                rs.getString("notes"));
    }

    private static Map<String, String> scoresAsStrings(Map<String, Double> scores) {
        var result = new LinkedHashMap<String, String>(scores.size());
        for (var entry : scores.entrySet()) {
            result.put(entry.getKey(), Double.toString(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Double> scoresFromStrings(Map<String, String> encoded) {
        var result = new LinkedHashMap<String, Double>(encoded.size());
        for (var entry : encoded.entrySet()) {
            try {
                result.put(entry.getKey(), Double.parseDouble(entry.getValue()));
            } catch (NumberFormatException nfe) {
                logger.trace("Skipping non-numeric persisted score {}={}",
                        entry.getKey(), entry.getValue(), nfe);
            }
        }
        return result;
    }
}
