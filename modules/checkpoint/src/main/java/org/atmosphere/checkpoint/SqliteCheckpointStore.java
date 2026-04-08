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

    private final Connection connection;
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

    /** Create a store at the given file path. */
    public SqliteCheckpointStore(Path dbPath) {
        this(toJdbcUrl(dbPath));
    }

    private SqliteCheckpointStore(String jdbcUrl) {
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
                        metadata_json TEXT,
                        created_at TEXT NOT NULL
                    )""");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_checkpoints_coord ON checkpoints(coordination_id)");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_checkpoints_agent ON checkpoints(agent_name)");
            }
            logger.info("SqliteCheckpointStore initialized");
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
                (id, parent_id, coordination_id, agent_name, state_json, metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
            try (var ps = connection.prepareStatement(sql)) {
                ps.setString(1, snapshot.id().value());
                ps.setString(2, snapshot.parentId() != null ? snapshot.parentId().value() : null);
                ps.setString(3, snapshot.coordinationId());
                ps.setString(4, snapshot.agentName());
                ps.setString(5, mapper.writeValueAsString(snapshot.state()));
                ps.setString(6, mapper.writeValueAsString(snapshot.metadata()));
                ps.setString(7, snapshot.createdAt().toString());
                ps.executeUpdate();
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
        var metadataJson = rs.getString("metadata_json");

        Object state = stateJson != null ? mapper.readValue(stateJson, Object.class) : null;
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
