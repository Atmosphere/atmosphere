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
package org.atmosphere.interactions.postgres;

import org.atmosphere.ai.TokenUsage;
import org.atmosphere.interactions.Interaction;
import org.atmosphere.interactions.InteractionQuery;
import org.atmosphere.interactions.InteractionStatus;
import org.atmosphere.interactions.InteractionStep;
import org.atmosphere.interactions.InteractionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link InteractionStore} backed by a JDBC {@link DataSource} — durable
 * interaction persistence that targets PostgreSQL but runs against any JSR-221
 * {@code DataSource} (tests exercise it against H2 in PostgreSQL-compatibility
 * mode).
 *
 * <p>Mirrors {@code SqliteInteractionStore}: two tables hold the data,
 * {@code interactions} (one header row per turn) and {@code interaction_steps}
 * (the durable {@code steps[]} log, keyed by {@code (interaction_id, seq)}).
 * {@link #save} upserts the header and {@link #appendStep} inserts step rows
 * incrementally so an in-flight background run is observable. The step
 * {@code data} map, and any per-step or aggregate {@link TokenUsage}, are
 * serialized to JSON via Jackson and stored as {@code TEXT}; timestamps are
 * stored as {@code BIGINT} epoch nanoseconds.</p>
 *
 * <h2>Portability</h2>
 * <p>The SQL is the intersection of PostgreSQL and H2's PostgreSQL mode: the
 * upsert is a transactional {@code DELETE}-then-{@code INSERT} (no Postgres-only
 * {@code ON CONFLICT}), all column names are {@code lower_snake_case}, JSON
 * payloads use {@code TEXT}, and timestamps use {@code BIGINT}.</p>
 *
 * <h2>Ownership</h2>
 * <p>The {@link DataSource} is supplied by the operator (who chooses the driver
 * and pooling); this store never closes it (Correctness Invariant #1 —
 * Ownership). {@link #start()} creates the schema; {@link #stop()} releases only
 * what this store opened (nothing, since connections are borrowed per call).</p>
 */
public final class PostgresInteractionStore implements InteractionStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresInteractionStore.class);

    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Create a store over the given JDBC source.
     *
     * @param dataSource the operator-supplied {@code DataSource}; never closed by
     *                   this store (the caller owns its lifecycle)
     */
    public PostgresInteractionStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public boolean isAvailable() {
        try (var conn = dataSource.getConnection()) {
            return conn != null;
        } catch (SQLException e) {
            LOGGER.debug("PostgresInteractionStore unavailable: {}", e.toString());
            return false;
        }
    }

    @Override
    public void start() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    id VARCHAR(255) PRIMARY KEY,
                    parent_id VARCHAR(255),
                    conversation_id VARCHAR(255),
                    agent_id VARCHAR(255),
                    user_id VARCHAR(255),
                    model VARCHAR(255),
                    status VARCHAR(32) NOT NULL,
                    background BOOLEAN NOT NULL,
                    store_flag BOOLEAN NOT NULL,
                    final_text TEXT,
                    usage_json TEXT,
                    error_message TEXT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )""");
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_interactions_user ON interactions (user_id)");
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_interactions_conv ON interactions (conversation_id)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS interaction_steps (
                    interaction_id VARCHAR(255) NOT NULL,
                    seq BIGINT NOT NULL,
                    type VARCHAR(64) NOT NULL,
                    text TEXT,
                    tool_name VARCHAR(255),
                    data_json TEXT,
                    usage_json TEXT,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (interaction_id, seq)
                )""");
            LOGGER.info("PostgresInteractionStore initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create interactions schema", e);
        }
    }

    @Override
    public void stop() {
        // The DataSource is owned by the caller; nothing to release here. Each
        // operation borrows and returns its own connection via try-with-resources.
        LOGGER.debug("PostgresInteractionStore stopped (DataSource left open — caller-owned)");
    }

    @Override
    public Interaction save(Interaction interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        try (var conn = dataSource.getConnection()) {
            var prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Portable upsert: DELETE-then-INSERT in one transaction. Avoids
                // Postgres-only "INSERT ... ON CONFLICT" so the same SQL runs on
                // H2's PostgreSQL-compat mode.
                try (var del = conn.prepareStatement(
                        "DELETE FROM interactions WHERE id = ?")) {
                    del.setString(1, interaction.id());
                    del.executeUpdate();
                }
                var sql = """
                    INSERT INTO interactions
                    (id, parent_id, conversation_id, agent_id, user_id, model, status,
                     background, store_flag, final_text, usage_json, error_message,
                     created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setString(1, interaction.id());
                    ps.setString(2, interaction.parentId());
                    ps.setString(3, interaction.conversationId());
                    ps.setString(4, interaction.agentId());
                    ps.setString(5, interaction.userId());
                    ps.setString(6, interaction.model());
                    ps.setString(7, interaction.status().name());
                    ps.setBoolean(8, interaction.background());
                    ps.setBoolean(9, interaction.store());
                    ps.setString(10, interaction.finalText());
                    ps.setString(11, writeUsage(interaction.usage()));
                    ps.setString(12, interaction.errorMessage());
                    ps.setLong(13, toEpochNanos(interaction.createdAt()));
                    ps.setLong(14, toEpochNanos(interaction.updatedAt()));
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
            return interaction;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save interaction " + interaction.id(), e);
        }
    }

    @Override
    public void appendStep(String interactionId, InteractionStep step) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");
        Objects.requireNonNull(step, "step must not be null");
        try (var conn = dataSource.getConnection()) {
            var prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Replace any existing (interaction_id, seq) row, then insert —
                // the portable equivalent of SQLite's INSERT OR REPLACE.
                try (var del = conn.prepareStatement(
                        "DELETE FROM interaction_steps WHERE interaction_id = ? AND seq = ?")) {
                    del.setString(1, interactionId);
                    del.setLong(2, step.seq());
                    del.executeUpdate();
                }
                var sql = """
                    INSERT INTO interaction_steps
                    (interaction_id, seq, type, text, tool_name, data_json, usage_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setString(1, interactionId);
                    ps.setLong(2, step.seq());
                    ps.setString(3, step.type());
                    ps.setString(4, step.text());
                    ps.setString(5, step.toolName());
                    ps.setString(6, step.data().isEmpty()
                            ? null : mapper.writeValueAsString(step.data()));
                    ps.setString(7, writeUsage(step.usage()));
                    ps.setLong(8, toEpochNanos(step.createdAt()));
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append step to " + interactionId, e);
        }
    }

    @Override
    public Optional<Interaction> load(String interactionId) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT * FROM interactions WHERE id = ?")) {
            ps.setString(1, interactionId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    var header = ResultRow.from(rs, mapper);
                    return Optional.of(header.toInteraction(loadSteps(conn, interactionId)));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load interaction " + interactionId, e);
        }
    }

    @Override
    public List<Interaction> list(InteractionQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        var sql = new StringBuilder("SELECT * FROM interactions WHERE 1=1");
        var params = new ArrayList<Object>();
        if (query.userId() != null) {
            sql.append(" AND user_id = ?");
            params.add(query.userId());
        }
        if (query.conversationId() != null) {
            sql.append(" AND conversation_id = ?");
            params.add(query.conversationId());
        }
        if (query.status() != null) {
            sql.append(" AND status = ?");
            params.add(query.status().name());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(query.limit());

        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                var param = params.get(i);
                if (param instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else {
                    ps.setString(i + 1, param.toString());
                }
            }
            var ids = new ArrayList<String>();
            var headers = new ArrayList<ResultRow>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    headers.add(ResultRow.from(rs, mapper));
                    ids.add(rs.getString("id"));
                }
            }
            var results = new ArrayList<Interaction>(headers.size());
            for (int i = 0; i < headers.size(); i++) {
                results.add(headers.get(i).toInteraction(loadSteps(conn, ids.get(i))));
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list interactions", e);
        }
    }

    @Override
    public boolean delete(String interactionId) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");
        try (var conn = dataSource.getConnection()) {
            var prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (var ps = conn.prepareStatement(
                        "DELETE FROM interaction_steps WHERE interaction_id = ?")) {
                    ps.setString(1, interactionId);
                    ps.executeUpdate();
                }
                boolean removed;
                try (var ps = conn.prepareStatement(
                        "DELETE FROM interactions WHERE id = ?")) {
                    ps.setString(1, interactionId);
                    removed = ps.executeUpdate() > 0;
                }
                conn.commit();
                return removed;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete interaction " + interactionId, e);
        }
    }

    private List<InteractionStep> loadSteps(Connection conn, String interactionId)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT * FROM interaction_steps WHERE interaction_id = ? ORDER BY seq ASC")) {
            ps.setString(1, interactionId);
            var steps = new ArrayList<InteractionStep>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var dataJson = rs.getString("data_json");
                    Map<String, Object> data = dataJson != null
                            ? mapper.readValue(dataJson, DATA_TYPE) : Map.of();
                    steps.add(new InteractionStep(
                            rs.getLong("seq"),
                            rs.getString("type"),
                            rs.getString("text"),
                            rs.getString("tool_name"),
                            data,
                            readUsage(rs.getString("usage_json")),
                            fromEpochNanos(rs.getLong("created_at"))));
                }
            }
            return steps;
        }
    }

    private String writeUsage(TokenUsage usage) {
        return usage != null ? mapper.writeValueAsString(usage) : null;
    }

    private TokenUsage readUsage(String json) {
        return json != null ? mapper.readValue(json, TokenUsage.class) : null;
    }

    private static long toEpochNanos(Instant instant) {
        return Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L) + instant.getNano();
    }

    private static Instant fromEpochNanos(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                Math.floorMod(nanos, 1_000_000_000L));
    }

    /** A decoded header row, awaiting its step list to become an {@link Interaction}. */
    private record ResultRow(String id, String parentId, String conversationId, String agentId,
                             String userId, String model, InteractionStatus status,
                             boolean background, boolean store, String finalText,
                             TokenUsage usage, String errorMessage, Instant createdAt,
                             Instant updatedAt) {

        static ResultRow from(ResultSet rs, ObjectMapper mapper) throws SQLException {
            var usageJson = rs.getString("usage_json");
            return new ResultRow(
                    rs.getString("id"),
                    rs.getString("parent_id"),
                    rs.getString("conversation_id"),
                    rs.getString("agent_id"),
                    rs.getString("user_id"),
                    rs.getString("model"),
                    InteractionStatus.valueOf(rs.getString("status")),
                    rs.getBoolean("background"),
                    rs.getBoolean("store_flag"),
                    rs.getString("final_text"),
                    usageJson != null ? mapper.readValue(usageJson, TokenUsage.class) : null,
                    rs.getString("error_message"),
                    fromEpochNanos(rs.getLong("created_at")),
                    fromEpochNanos(rs.getLong("updated_at")));
        }

        Interaction toInteraction(List<InteractionStep> steps) {
            return new Interaction(id, parentId, conversationId, agentId, userId, model,
                    status, background, store, steps, finalText, usage, errorMessage,
                    createdAt, updatedAt);
        }
    }
}
