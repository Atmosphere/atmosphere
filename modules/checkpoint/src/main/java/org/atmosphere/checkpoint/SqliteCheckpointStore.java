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
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link CheckpointStore} backed by an embedded SQLite database. Provides
 * durable checkpoint persistence across JVM restarts using the same SQLite
 * infrastructure as {@code atmosphere-durable-sessions-sqlite}.
 *
 * <p>Defaults to {@code atmosphere-checkpoints.db} in the working directory.
 * The state {@code S} is serialized to JSON via Jackson.</p>
 */
public final class SqliteCheckpointStore implements CheckpointStore {

    private static final Logger logger = LoggerFactory.getLogger(SqliteCheckpointStore.class);

    /**
     * Default upper bound on total retained snapshots. Beyond this the oldest
     * rows are pruned on save so the table cannot grow without bound
     * (Correctness Invariant #3 — backpressure). Matches
     * {@link InMemoryCheckpointStore#DEFAULT_MAX_SNAPSHOTS} and
     * {@code PostgresCheckpointStore} so all three backends bound storage
     * identically.
     *
     * <p><strong>Resume-anchor caveat:</strong> this is a <em>global</em>
     * evict-oldest cap, so under sustained write pressure (more than the cap of
     * newer snapshots from other coordinations) it can evict the newest
     * snapshot — the resume anchor — of a dormant, hibernated coordination.
     * That is an accepted limitation of a fixed-size snapshot store: bounding
     * total size and never evicting a non-terminal run's anchor cannot both
     * hold without a per-run terminal-status concept, which this SPI does not
     * model. Durable agent-run resume therefore does not rely on this store's
     * retention; it uses the dedicated run journal whose retention is
     * terminal-status-aware. Applications using this store for explicit
     * {@code Workflow} checkpoints should reap completed runs via
     * {@link #deleteCoordination(String)} to stay well under the cap.</p>
     */
    public static final int DEFAULT_MAX_SNAPSHOTS = 10_000;

    private final Connection connection;
    private final int maxSnapshots;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<CheckpointListener> listeners = new CopyOnWriteArrayList<>();
    @SuppressWarnings("unchecked")
    private static final tools.jackson.core.type.TypeReference<Map<String, String>> MAP_TYPE =
            new tools.jackson.core.type.TypeReference<>() { };

    /** Create a store with the default database file. */
    public SqliteCheckpointStore() {
        this(Path.of("atmosphere-checkpoints.db"));
    }

    /** Create a store at the given file path with the default snapshot cap. */
    public SqliteCheckpointStore(Path dbPath) {
        this(dbPath, DEFAULT_MAX_SNAPSHOTS);
    }

    /**
     * Create a store at the given file path retaining at most
     * {@code maxSnapshots} snapshots in total (oldest pruned on save).
     */
    public SqliteCheckpointStore(Path dbPath, int maxSnapshots) {
        this(toJdbcUrl(dbPath), maxSnapshots);
    }

    private SqliteCheckpointStore(String jdbcUrl, int maxSnapshots) {
        if (maxSnapshots <= 0) {
            throw new IllegalArgumentException(
                    "maxSnapshots must be positive, got " + maxSnapshots);
        }
        this.maxSnapshots = maxSnapshots;
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite database: " + jdbcUrl, e);
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

    @Override
    public void start() {
        lock.lock();
        try {
            try (var stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS checkpoints (
                        id TEXT PRIMARY KEY,
                        parent_id TEXT,
                        coordination_id TEXT NOT NULL,
                        agent_name TEXT,
                        state_json TEXT,
                        state_type TEXT,
                        metadata_json TEXT,
                        created_at TEXT NOT NULL
                    )""");
                // Migration for databases created before state_type existed.
                // SQLite has no ADD COLUMN IF NOT EXISTS, so check presence
                // first — this keeps start() idempotent without throwing a
                // duplicate-column error on every boot of a current-schema DB.
                if (!hasColumn("checkpoints", "state_type")) {
                    stmt.execute("ALTER TABLE checkpoints ADD COLUMN state_type TEXT");
                }
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_checkpoints_coord ON checkpoints(coordination_id)");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_checkpoints_agent ON checkpoints(agent_name)");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_checkpoints_created ON checkpoints(created_at)");
            }
            logger.info("SqliteCheckpointStore initialized (maxSnapshots={})", maxSnapshots);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create checkpoints table", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            connection.close();
            listeners.clear();
        } catch (SQLException e) {
            logger.warn("Error closing SQLite connection", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> WorkflowSnapshot<S> save(WorkflowSnapshot<S> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        lock.lock();
        try {
            var sql = """
                INSERT OR REPLACE INTO checkpoints
                (id, parent_id, coordination_id, agent_name, state_json, state_type,
                 metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
            try (var ps = connection.prepareStatement(sql)) {
                ps.setString(1, snapshot.id().value());
                ps.setString(2, snapshot.parentId() != null ? snapshot.parentId().value() : null);
                ps.setString(3, snapshot.coordinationId());
                ps.setString(4, snapshot.agentName());
                ps.setString(5, mapper.writeValueAsString(snapshot.state()));
                ps.setString(6, snapshot.state() != null ? snapshot.state().getClass().getName() : null);
                ps.setString(7, mapper.writeValueAsString(snapshot.metadata()));
                ps.setString(8, snapshot.createdAt().toString());
                ps.executeUpdate();
            }
            // Best-effort prune: the snapshot is already durably committed
            // (autocommit), so a prune failure must neither report the save as
            // failed nor suppress the Saved event (Correctness Invariant #2).
            try {
                pruneIfNeeded();
            } catch (SQLException prune) {
                logger.warn("Checkpoint prune failed — snapshot saved, "
                        + "size cap not enforced this round", prune);
            }
            dispatch(new CheckpointEvent.Saved(
                    snapshot.id(), snapshot.coordinationId(), Instant.now()));
            return snapshot;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save checkpoint " + snapshot.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> Optional<WorkflowSnapshot<S>> load(CheckpointId id) {
        Objects.requireNonNull(id, "id must not be null");
        lock.lock();
        try {
            try (var ps = connection.prepareStatement(
                    "SELECT * FROM checkpoints WHERE id = ?")) {
                ps.setString(1, id.value());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var snapshot = (WorkflowSnapshot<S>) fromRow(rs);
                        dispatch(new CheckpointEvent.Loaded(
                                id, snapshot.coordinationId(), Instant.now()));
                        return Optional.of(snapshot);
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load checkpoint " + id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> WorkflowSnapshot<S> fork(CheckpointId sourceId, S newState) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        var source = (WorkflowSnapshot<S>) load(sourceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source checkpoint: " + sourceId));
        var forked = WorkflowSnapshot.<S>builder()
                .id(CheckpointId.random())
                .parentId(sourceId)
                .coordinationId(source.coordinationId())
                .agentName(source.agentName())
                .state(newState)
                .metadata(source.metadata())
                .createdAt(Instant.now())
                .build();
        save(forked);
        dispatch(new CheckpointEvent.Forked(
                forked.id(), sourceId, forked.coordinationId(), Instant.now()));
        return forked;
    }

    @Override
    public List<WorkflowSnapshot<?>> list(CheckpointQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        lock.lock();
        try {
            var sql = new StringBuilder("SELECT * FROM checkpoints WHERE 1=1");
            var params = new ArrayList<Object>();
            if (query.coordinationId() != null) {
                sql.append(" AND coordination_id = ?");
                params.add(query.coordinationId());
            }
            if (query.agentName() != null) {
                sql.append(" AND agent_name = ?");
                params.add(query.agentName());
            }
            if (query.since() != null) {
                sql.append(" AND created_at >= ?");
                params.add(query.since().toString());
            }
            if (query.until() != null) {
                sql.append(" AND created_at <= ?");
                params.add(query.until().toString());
            }
            sql.append(" ORDER BY created_at ASC, id ASC");
            if (query.limit() > 0) {
                sql.append(" LIMIT ?");
                params.add(query.limit());
            }

            try (var ps = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    var param = params.get(i);
                    if (param instanceof Integer n) {
                        ps.setInt(i + 1, n);
                    } else {
                        ps.setString(i + 1, param.toString());
                    }
                }
                var results = new ArrayList<WorkflowSnapshot<?>>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(fromRow(rs));
                    }
                }
                return results;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list checkpoints", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(CheckpointId id) {
        Objects.requireNonNull(id, "id must not be null");
        lock.lock();
        try {
            // Read coordination_id before deleting for the event
            String coordId = null;
            try (var ps = connection.prepareStatement(
                    "SELECT coordination_id FROM checkpoints WHERE id = ?")) {
                ps.setString(1, id.value());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        coordId = rs.getString("coordination_id");
                    }
                }
            }
            try (var ps = connection.prepareStatement(
                    "DELETE FROM checkpoints WHERE id = ?")) {
                ps.setString(1, id.value());
                var deleted = ps.executeUpdate() > 0;
                if (deleted && coordId != null) {
                    dispatch(new CheckpointEvent.Deleted(id, coordId, Instant.now()));
                }
                return deleted;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete checkpoint " + id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int deleteCoordination(String coordinationId) {
        Objects.requireNonNull(coordinationId, "coordinationId must not be null");
        lock.lock();
        try {
            try (var ps = connection.prepareStatement(
                    "DELETE FROM checkpoints WHERE coordination_id = ?")) {
                ps.setString(1, coordinationId);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to delete coordination " + coordinationId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addListener(CheckpointListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    @Override
    public void removeListener(CheckpointListener listener) {
        listeners.remove(listener);
    }

    private WorkflowSnapshot<?> fromRow(ResultSet rs) throws Exception {
        var parentIdStr = rs.getString("parent_id");
        var stateJson = rs.getString("state_json");
        var stateType = rs.getString("state_type");
        var metadataJson = rs.getString("metadata_json");

        Object state = deserializeState(stateJson, stateType);
        Map<String, String> metadata = metadataJson != null
                ? mapper.readValue(metadataJson, MAP_TYPE) : Map.of();

        return WorkflowSnapshot.builder()
                .id(CheckpointId.of(rs.getString("id")))
                .parentId(parentIdStr != null ? CheckpointId.of(parentIdStr) : null)
                .coordinationId(rs.getString("coordination_id"))
                .agentName(rs.getString("agent_name"))
                .state(state)
                .metadata(metadata)
                .createdAt(Instant.parse(rs.getString("created_at")))
                .build();
    }

    /**
     * Deserialize the persisted state, restoring its original Java type when
     * {@code stateType} (the class name recorded at save time) is present and
     * resolvable. Falls back to generic {@code Object} mapping — the pre-fix
     * behaviour — for legacy rows with no recorded type, for classes no longer
     * on the classpath ({@code ClassNotFoundException}/{@code LinkageError}),
     * and for types Jackson cannot reconstruct (e.g. JDK immutable collections
     * such as {@code Map.of(...)}). This keeps structured records (the
     * {@code ClassCastException} case) faithful while never regressing
     * collection/string state. A type-loss fallback logs at {@code WARN} so a
     * record silently degrading to a {@code Map} is observable.
     *
     * <p><strong>Fidelity boundary:</strong> only the <em>top-level</em>
     * concrete/record type round-trips. A raw generic-container state (e.g.
     * {@code List<Person>}) records as {@code java.util.ArrayList} and its
     * elements still deserialize generically — wrap such state in a record to
     * retain element types.</p>
     *
     * <p><strong>Trust boundary:</strong> {@code stateType} is honored as a
     * class selector for {@code Class.forName} + {@code readValue}. No Jackson
     * default typing is enabled (the mapper is a bare {@code ObjectMapper}, so
     * there is no embedded-{@code @class} polymorphic vector), but a caller able
     * to write the {@code state_type} column could still steer instantiation to
     * any loadable class. The durable backend must therefore be a trusted,
     * application-exclusive store. Do not enable {@code activateDefaultTyping}.</p>
     */
    private Object deserializeState(String stateJson, String stateType) throws Exception {
        if (stateJson == null) {
            return null;
        }
        if (stateType != null && !stateType.isBlank()) {
            try {
                var clazz = Class.forName(stateType, false, stateClassLoader());
                return mapper.readValue(stateJson, clazz);
            } catch (Exception | LinkageError typeUnavailable) {
                logger.warn("Could not deserialize checkpoint state as {} — "
                        + "falling back to generic mapping: {}", stateType, typeUnavailable.toString());
            }
        }
        return mapper.readValue(stateJson, Object.class);
    }

    private static ClassLoader stateClassLoader() {
        var tccl = Thread.currentThread().getContextClassLoader();
        return tccl != null ? tccl : SqliteCheckpointStore.class.getClassLoader();
    }

    /**
     * Whether {@code column} exists on {@code table}, via {@code PRAGMA
     * table_info}. {@code table} is an internal literal, never user input.
     */
    private boolean hasColumn(String table, String column) throws SQLException {
        try (var ps = connection.prepareStatement("PRAGMA table_info(" + table + ")");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Bound the table to {@link #maxSnapshots} by deleting the oldest rows
     * beyond the cap (Correctness Invariant #3), mirroring
     * {@code InMemoryCheckpointStore}'s global evict-oldest policy. Runs under
     * the same lock as {@code save}; the {@code COUNT(*)} short-circuits the
     * prune when within bounds.
     *
     * <p>Oldest is determined by SQLite's implicit {@code rowid} (monotonic
     * with insertion / re-save), not by the {@code created_at} TEXT column —
     * lexicographic ordering of variable-precision ISO-8601 strings is not
     * strictly chronological, so {@code rowid} gives a correct, stable eviction
     * order.</p>
     */
    private void pruneIfNeeded() throws SQLException {
        int total;
        try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM checkpoints");
             var rs = ps.executeQuery()) {
            total = rs.next() ? rs.getInt(1) : 0;
        }
        if (total <= maxSnapshots) {
            return;
        }
        try (var ps = connection.prepareStatement(
                "DELETE FROM checkpoints WHERE id IN ("
                        + "SELECT id FROM checkpoints ORDER BY rowid ASC LIMIT ?)")) {
            ps.setInt(1, total - maxSnapshots);
            ps.executeUpdate();
        }
    }

    private void dispatch(CheckpointEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                logger.warn("CheckpointListener threw an exception handling {}", event, ex);
            }
        }
    }
}
