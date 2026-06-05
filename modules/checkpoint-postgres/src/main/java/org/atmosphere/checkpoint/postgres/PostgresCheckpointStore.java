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
package org.atmosphere.checkpoint.postgres;

import org.atmosphere.checkpoint.CheckpointEvent;
import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointListener;
import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link CheckpointStore} backed by a JDBC {@link DataSource}. Targets
 * PostgreSQL but is written against portable SQL so the same code runs
 * identically on Postgres and on H2's PostgreSQL-compatibility mode (which the
 * test suite uses).
 *
 * <p>The operator supplies the {@link DataSource} (driver + pooling); this
 * module pins no JDBC driver. Following Atmosphere's ownership invariant the
 * store <em>never</em> closes the {@code DataSource} — it only created the
 * table and owns nothing else.</p>
 *
 * <p>The state {@code S} and metadata map are serialized to JSON via Jackson 3
 * and stored in {@code TEXT} columns; {@code created_at} is stored as epoch
 * milliseconds in a {@code BIGINT} column. Upsert is implemented as a
 * transactional {@code DELETE}-then-{@code INSERT} (no Postgres-only
 * {@code ON CONFLICT}) so it behaves identically on every JDBC backend.</p>
 */
public final class PostgresCheckpointStore implements CheckpointStore {

    private static final Logger logger = LoggerFactory.getLogger(PostgresCheckpointStore.class);

    /** Upper bound on registered listeners to keep the collection from growing without limit. */
    private static final int MAX_LISTENERS = 1024;

    private static final tools.jackson.core.type.TypeReference<Map<String, String>> MAP_TYPE =
            new tools.jackson.core.type.TypeReference<>() { };

    private final DataSource dataSource;
    private final String table;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<CheckpointListener> listeners = new CopyOnWriteArrayList<>();

    /** Build against the default table name {@code checkpoints}. */
    public PostgresCheckpointStore(DataSource dataSource) {
        this(dataSource, "checkpoints");
    }

    /**
     * @param dataSource JDBC source; the store never closes it (owned by caller)
     * @param table      table name; must be a simple identifier
     */
    public PostgresCheckpointStore(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "table must be a simple identifier (alphanumeric + underscore), got: " + table);
        }
        this.table = table;
    }

    @Override
    public void start() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            // Portable DDL: TEXT/VARCHAR for serialized JSON, BIGINT for the
            // timestamp. CREATE TABLE IF NOT EXISTS makes start() idempotent.
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id VARCHAR(255) PRIMARY KEY, "
                    + "parent_id VARCHAR(255), "
                    + "coordination_id VARCHAR(255) NOT NULL, "
                    + "agent_name VARCHAR(255), "
                    + "state_json TEXT, "
                    + "metadata_json TEXT, "
                    + "created_at BIGINT NOT NULL)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + table
                    + "_coord ON " + table + " (coordination_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + table
                    + "_agent ON " + table + " (agent_name)");
            logger.info("PostgresCheckpointStore initialized (table={})", table);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create checkpoints table " + table, e);
        }
    }

    @Override
    public void stop() {
        // The DataSource is owned by the caller — do NOT close it. Only release
        // resources this store created: the listener registrations.
        listeners.clear();
    }

    @Override
    public <S> WorkflowSnapshot<S> save(WorkflowSnapshot<S> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        try (var conn = dataSource.getConnection()) {
            var previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Portable upsert: DELETE-then-INSERT inside one transaction
                // (no Postgres-only INSERT ... ON CONFLICT).
                try (var del = conn.prepareStatement(
                        "DELETE FROM " + table + " WHERE id = ?")) {
                    del.setString(1, snapshot.id().value());
                    del.executeUpdate();
                }
                try (var ins = conn.prepareStatement("INSERT INTO " + table
                        + " (id, parent_id, coordination_id, agent_name, state_json, metadata_json,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    ins.setString(1, snapshot.id().value());
                    ins.setString(2, snapshot.parentId() != null ? snapshot.parentId().value() : null);
                    ins.setString(3, snapshot.coordinationId());
                    ins.setString(4, snapshot.agentName());
                    ins.setString(5, mapper.writeValueAsString(snapshot.state()));
                    ins.setString(6, mapper.writeValueAsString(snapshot.metadata()));
                    ins.setLong(7, snapshot.createdAt().toEpochMilli());
                    ins.executeUpdate();
                }
                conn.commit();
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
            dispatch(new CheckpointEvent.Saved(
                    snapshot.id(), snapshot.coordinationId(), Instant.now()));
            return snapshot;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save checkpoint " + snapshot.id(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> Optional<WorkflowSnapshot<S>> load(CheckpointId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT id, parent_id, coordination_id, agent_name,"
                     + " state_json, metadata_json, created_at FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id.value());
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    var snapshot = (WorkflowSnapshot<S>) fromRow(rs);
                    dispatch(new CheckpointEvent.Loaded(
                            id, snapshot.coordinationId(), Instant.now()));
                    return Optional.of(snapshot);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load checkpoint " + id, e);
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
        var sql = new StringBuilder("SELECT id, parent_id, coordination_id, agent_name,"
                + " state_json, metadata_json, created_at FROM " + table + " WHERE 1=1");
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
            params.add(query.since().toEpochMilli());
        }
        if (query.until() != null) {
            sql.append(" AND created_at <= ?");
            params.add(query.until().toEpochMilli());
        }
        sql.append(" ORDER BY created_at ASC, id ASC");
        if (query.limit() > 0) {
            sql.append(" LIMIT ?");
            params.add(query.limit());
        }

        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                var param = params.get(i);
                if (param instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else if (param instanceof Long l) {
                    ps.setLong(i + 1, l);
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list checkpoints", e);
        }
    }

    @Override
    public boolean delete(CheckpointId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (var conn = dataSource.getConnection()) {
            // Read coordination_id before deleting so the event carries it.
            String coordId = null;
            try (var ps = conn.prepareStatement(
                    "SELECT coordination_id FROM " + table + " WHERE id = ?")) {
                ps.setString(1, id.value());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        coordId = rs.getString("coordination_id");
                    }
                }
            }
            try (var ps = conn.prepareStatement(
                    "DELETE FROM " + table + " WHERE id = ?")) {
                ps.setString(1, id.value());
                var deleted = ps.executeUpdate() > 0;
                if (deleted && coordId != null) {
                    dispatch(new CheckpointEvent.Deleted(id, coordId, Instant.now()));
                }
                return deleted;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete checkpoint " + id, e);
        }
    }

    @Override
    public int deleteCoordination(String coordinationId) {
        Objects.requireNonNull(coordinationId, "coordinationId must not be null");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "DELETE FROM " + table + " WHERE coordination_id = ?")) {
            ps.setString(1, coordinationId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to delete coordination " + coordinationId, e);
        }
    }

    @Override
    public void addListener(CheckpointListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        if (listeners.size() >= MAX_LISTENERS) {
            throw new IllegalStateException(
                    "Listener limit reached (" + MAX_LISTENERS + "); refusing to register more");
        }
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
                .createdAt(Instant.ofEpochMilli(rs.getLong("created_at")))
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
