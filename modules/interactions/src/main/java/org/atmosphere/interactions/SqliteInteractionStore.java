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
package org.atmosphere.interactions;

import org.atmosphere.ai.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link InteractionStore} backed by an embedded SQLite database — durable
 * interaction persistence across JVM restarts, mirroring the infrastructure of
 * {@code SqliteCheckpointStore}.
 *
 * <p>Defaults to {@code atmosphere-interactions.db} in the working directory.
 * Two tables hold the data: {@code interactions} (one header row per turn) and
 * {@code interaction_steps} (the durable {@code steps[]} log, keyed by
 * {@code (interaction_id, seq)}). {@link #save} upserts the header;
 * {@link #appendStep} inserts step rows incrementally so an in-flight background
 * run is observable. The step payload {@code data}, and any per-step or aggregate
 * {@link TokenUsage}, are serialized to JSON via Jackson.</p>
 */
public final class SqliteInteractionStore implements InteractionStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteInteractionStore.class);

    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() { };

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Create a store with the default database file. */
    public SqliteInteractionStore() {
        this(Path.of("atmosphere-interactions.db"));
    }

    /** Create a store at the given file path. */
    public SqliteInteractionStore(Path dbPath) {
        this(toJdbcUrl(dbPath));
    }

    private SqliteInteractionStore(String jdbcUrl) {
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
                    CREATE TABLE IF NOT EXISTS interactions (
                        id TEXT PRIMARY KEY,
                        parent_id TEXT,
                        conversation_id TEXT,
                        agent_id TEXT,
                        user_id TEXT,
                        model TEXT,
                        status TEXT NOT NULL,
                        background INTEGER NOT NULL,
                        store_flag INTEGER NOT NULL,
                        final_text TEXT,
                        usage_json TEXT,
                        error_message TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )""");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_interactions_user ON interactions(user_id)");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_interactions_conv ON interactions(conversation_id)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS interaction_steps (
                        interaction_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        text TEXT,
                        tool_name TEXT,
                        data_json TEXT,
                        usage_json TEXT,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (interaction_id, seq)
                    )""");
            }
            LOGGER.info("SqliteInteractionStore initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create interactions schema", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.warn("Error closing SQLite connection", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Interaction save(Interaction interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        lock.lock();
        try {
            var sql = """
                INSERT OR REPLACE INTO interactions
                (id, parent_id, conversation_id, agent_id, user_id, model, status,
                 background, store_flag, final_text, usage_json, error_message,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            try (var ps = connection.prepareStatement(sql)) {
                ps.setString(1, interaction.id());
                ps.setString(2, interaction.parentId());
                ps.setString(3, interaction.conversationId());
                ps.setString(4, interaction.agentId());
                ps.setString(5, interaction.userId());
                ps.setString(6, interaction.model());
                ps.setString(7, interaction.status().name());
                ps.setInt(8, interaction.background() ? 1 : 0);
                ps.setInt(9, interaction.store() ? 1 : 0);
                ps.setString(10, interaction.finalText());
                ps.setString(11, writeUsage(interaction.usage()));
                ps.setString(12, interaction.errorMessage());
                ps.setString(13, interaction.createdAt().toString());
                ps.setString(14, interaction.updatedAt().toString());
                ps.executeUpdate();
            }
            return interaction;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save interaction " + interaction.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void appendStep(String interactionId, InteractionStep step) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");
        Objects.requireNonNull(step, "step must not be null");
        lock.lock();
        try {
            var sql = """
                INSERT OR REPLACE INTO interaction_steps
                (interaction_id, seq, type, text, tool_name, data_json, usage_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
            try (var ps = connection.prepareStatement(sql)) {
                ps.setString(1, interactionId);
                ps.setLong(2, step.seq());
                ps.setString(3, step.type());
                ps.setString(4, step.text());
                ps.setString(5, step.toolName());
                ps.setString(6, step.data().isEmpty() ? null : mapper.writeValueAsString(step.data()));
                ps.setString(7, writeUsage(step.usage()));
                ps.setString(8, step.createdAt().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append step to " + interactionId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Interaction> load(String interactionId) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");
        lock.lock();
        try {
            try (var ps = connection.prepareStatement(
                    "SELECT * FROM interactions WHERE id = ?")) {
                ps.setString(1, interactionId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(headerFromRow(rs, loadSteps(interactionId)));
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load interaction " + interactionId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Interaction> list(InteractionQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        lock.lock();
        try {
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

            try (var ps = connection.prepareStatement(sql.toString())) {
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
                    results.add(headers.get(i).toInteraction(loadSteps(ids.get(i))));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list interactions", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(String interactionId) {
        Objects.requireNonNull(interactionId, "interactionId must not be null");
        lock.lock();
        try {
            try (var ps = connection.prepareStatement(
                    "DELETE FROM interaction_steps WHERE interaction_id = ?")) {
                ps.setString(1, interactionId);
                ps.executeUpdate();
            }
            try (var ps = connection.prepareStatement(
                    "DELETE FROM interactions WHERE id = ?")) {
                ps.setString(1, interactionId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete interaction " + interactionId, e);
        } finally {
            lock.unlock();
        }
    }

    private List<InteractionStep> loadSteps(String interactionId) throws SQLException {
        try (var ps = connection.prepareStatement(
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
                            Instant.parse(rs.getString("created_at"))));
                }
            }
            return steps;
        }
    }

    private Interaction headerFromRow(ResultSet rs, List<InteractionStep> steps) throws SQLException {
        return ResultRow.from(rs, mapper).toInteraction(steps);
    }

    private String writeUsage(TokenUsage usage) {
        return usage != null ? mapper.writeValueAsString(usage) : null;
    }

    private TokenUsage readUsage(String json) {
        return json != null ? mapper.readValue(json, TokenUsage.class) : null;
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
                    rs.getInt("background") == 1,
                    rs.getInt("store_flag") == 1,
                    rs.getString("final_text"),
                    usageJson != null ? mapper.readValue(usageJson, TokenUsage.class) : null,
                    rs.getString("error_message"),
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("updated_at")));
        }

        Interaction toInteraction(List<InteractionStep> steps) {
            return new Interaction(id, parentId, conversationId, agentId, userId, model,
                    status, background, store, steps, finalText, usage, errorMessage,
                    createdAt, updatedAt);
        }
    }
}
