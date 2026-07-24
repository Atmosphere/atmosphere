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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Crash-durable {@link DurableTimerStore} backed by an embedded SQLite database.
 * The {@link InMemoryDurableTimerStore} loses every armed timer when the JVM
 * dies; this backend persists them so a {@link DurableTimerService} built over
 * the same database file after a restart re-arms and fires timers that became
 * due while the process was down (restart survival).
 *
 * <h2>Schema</h2>
 *
 * A single {@code durable_timer} table keyed by the timer id. The payload map is
 * stored as JSON in one column via Jackson — the same serialization path
 * {@link SqliteCheckpointStore} uses — so no ad-hoc delimiter framing is applied
 * to the value (Correctness Invariant #4).
 *
 * <h2>Ownership (Correctness Invariant #1)</h2>
 *
 * The store opens and owns its own {@link Connection}; {@link #close()} closes
 * it. The {@link DurableTimerService} it backs never closes this connection —
 * it did not create it — so the store must be closed by whoever built it.
 *
 * <h2>Exactly-once claim (Correctness Invariant #2)</h2>
 *
 * {@link #remove(String)} is an atomic {@code DELETE ... WHERE id = ?} that
 * reports success only when it deleted exactly one row. That is the claim gate
 * {@link DurableTimerService} relies on so two concurrent pollers cannot both
 * fire the same timer.
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * Armed timers are all live — none is terminal, so there is nothing safe to
 * evict. The store is therefore bounded by <em>rejecting</em> a genuinely new
 * timer past {@link #maxTimers()} with a {@link RejectedExecutionException}
 * (a re-{@link #save} of an existing id is an update and always allowed),
 * mirroring {@link SqliteEffectJournal}'s "fail rather than drop" policy rather
 * than silently dropping a scheduled fire.
 *
 * @since 4.0
 */
public final class SqliteDurableTimerStore implements DurableTimerStore, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SqliteDurableTimerStore.class);

    /** Default cap on concurrently armed timers (a new timer past it is rejected). */
    public static final int DEFAULT_MAX_TIMERS = 100_000;

    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE =
            new TypeReference<>() { };

    private final Connection connection;
    private final int maxTimers;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();

    /** Open at the given file with the default timer cap. */
    public SqliteDurableTimerStore(Path dbPath) {
        this(dbPath, DEFAULT_MAX_TIMERS);
    }

    /** Open at the given file retaining at most {@code maxTimers} armed timers. */
    public SqliteDurableTimerStore(Path dbPath, int maxTimers) {
        if (maxTimers <= 0) {
            throw new IllegalArgumentException("maxTimers must be > 0, got " + maxTimers);
        }
        this.maxTimers = maxTimers;
        try {
            this.connection = DriverManager.getConnection(toJdbcUrl(dbPath));
            connection.setAutoCommit(true);
            createSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite durable-timer store: " + dbPath, e);
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
                CREATE TABLE IF NOT EXISTS durable_timer (
                    id TEXT PRIMARY KEY,
                    fire_at TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    payload TEXT NOT NULL
                )""");
        }
    }

    @Override
    public void save(DurableTimer timer) {
        Objects.requireNonNull(timer, "timer");
        lock.lock();
        try {
            if (!exists(timer.id()) && count() >= maxTimers) {
                throw new RejectedExecutionException("Durable-timer cap exceeded (maxTimers="
                        + maxTimers + "); rejecting new timer " + timer.id()
                        + " rather than dropping an armed timer");
            }
            try (var ps = connection.prepareStatement("INSERT OR REPLACE INTO durable_timer "
                    + "(id, fire_at, kind, payload) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, timer.id());
                ps.setString(2, timer.fireAt().toString());
                ps.setString(3, timer.kind());
                ps.setString(4, mapper.writeValueAsString(timer.payload()));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save durable timer " + timer.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<DurableTimer> all() {
        lock.lock();
        try (var ps = connection.prepareStatement(
                "SELECT id, fire_at, kind, payload FROM durable_timer")) {
            var out = new ArrayList<DurableTimer>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> payload = mapper.readValue(rs.getString("payload"), PAYLOAD_TYPE);
                    out.add(new DurableTimer(
                            rs.getString("id"),
                            Instant.parse(rs.getString("fire_at")),
                            rs.getString("kind"),
                            payload));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load durable timers", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(String id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try (var ps = connection.prepareStatement("DELETE FROM durable_timer WHERE id = ?")) {
            ps.setString(1, id);
            // Exactly one deleted row == this call claimed the timer.
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove durable timer " + id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String name() {
        return "sqlite";
    }

    /** Visible for tests / admin: the configured armed-timer cap. */
    public int maxTimers() {
        return maxTimers;
    }

    /** Visible for tests / admin: number of timers currently armed. */
    public int timerCount() {
        lock.lock();
        try {
            return count();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count durable timers", e);
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
            logger.warn("Error closing SQLite durable-timer store connection", e);
        } finally {
            lock.unlock();
        }
    }

    // --- helpers (all called under the lock) ---------------------------------

    private boolean exists(String id) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT 1 FROM durable_timer WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int count() throws SQLException {
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM durable_timer");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
