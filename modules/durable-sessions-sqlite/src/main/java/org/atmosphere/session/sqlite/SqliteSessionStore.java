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
package org.atmosphere.session.sqlite;

import org.atmosphere.session.DurableSession;
import org.atmosphere.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link SessionStore} backed by an embedded SQLite database.
 *
 * <p>Zero configuration — just add the JAR and sessions are persisted
 * to a local file. Perfect for single-node deployments, development,
 * and edge/IoT scenarios.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var store = new SqliteSessionStore();                           // default: atmosphere-sessions.db
 * var store = new SqliteSessionStore(Path.of("/data/sessions")); // custom path
 * var store = SqliteSessionStore.inMemory();                     // for testing
 * }</pre>
 */
public class SqliteSessionStore implements SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(SqliteSessionStore.class);

    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<Set<String>> SET_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() { };

    /**
     * Create a store with the default database file ({@code atmosphere-sessions.db}
     * in the current working directory).
     */
    public SqliteSessionStore() {
        this(Path.of("atmosphere-sessions.db"));
    }

    /**
     * Create a store at the given file path.
     * Parent directories are created automatically if they do not exist.
     */
    public SqliteSessionStore(Path dbPath) {
        this(ensureParentDirs(dbPath));
    }

    private static String ensureParentDirs(Path dbPath) {
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

    /**
     * Create an in-memory store (for testing).
     */
    public static SqliteSessionStore inMemory() {
        return new SqliteSessionStore("jdbc:sqlite::memory:");
    }

    private SqliteSessionStore(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            try (var stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            createTable();
            logger.info("SQLite session store initialized: {}", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite session store", e);
        }
    }

    private void createTable() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS durable_sessions (
                        token        TEXT PRIMARY KEY,
                        resource_id  TEXT NOT NULL,
                        rooms        TEXT NOT NULL DEFAULT '',
                        broadcasters TEXT NOT NULL DEFAULT '',
                        metadata     TEXT NOT NULL DEFAULT '',
                        created_at   INTEGER NOT NULL,
                        last_seen    INTEGER NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_last_seen
                    ON durable_sessions(last_seen)
                    """);
        }
    }

    @Override
    public synchronized void save(DurableSession session) {
        var sql = """
                INSERT OR REPLACE INTO durable_sessions
                (token, resource_id, rooms, broadcasters, metadata, created_at, last_seen)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, session.token());
            stmt.setString(2, session.resourceId());
            stmt.setString(3, toJson(session.rooms()));
            stmt.setString(4, toJson(session.broadcasters()));
            stmt.setString(5, toJson(session.metadata()));
            stmt.setLong(6, session.createdAt().toEpochMilli());
            stmt.setLong(7, session.lastSeen().toEpochMilli());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save session {}", session.token(), e);
        }
    }

    @Override
    public synchronized Optional<DurableSession> restore(String token) {
        var sql = "SELECT * FROM durable_sessions WHERE token = ?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, token);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new DurableSession(
                            rs.getString("token"),
                            rs.getString("resource_id"),
                            fromJsonSet(rs.getString("rooms")),
                            fromJsonSet(rs.getString("broadcasters")),
                            fromJsonMap(rs.getString("metadata")),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            Instant.ofEpochMilli(rs.getLong("last_seen"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to restore session {}", token, e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void remove(String token) {
        try (var stmt = connection.prepareStatement(
                "DELETE FROM durable_sessions WHERE token = ?")) {
            stmt.setString(1, token);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove session {}", token, e);
        }
    }

    @Override
    public synchronized void touch(String token) {
        try (var stmt = connection.prepareStatement(
                "UPDATE durable_sessions SET last_seen = ? WHERE token = ?")) {
            stmt.setLong(1, Instant.now().toEpochMilli());
            stmt.setString(2, token);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to touch session {}", token, e);
        }
    }

    @Override
    public synchronized List<DurableSession> removeExpired(Duration ttl) {
        var cutoff = Instant.now().minus(ttl).toEpochMilli();
        var expired = new ArrayList<DurableSession>();

        try (var select = connection.prepareStatement(
                "SELECT * FROM durable_sessions WHERE last_seen < ?")) {
            select.setLong(1, cutoff);
            try (var rs = select.executeQuery()) {
                while (rs.next()) {
                    expired.add(new DurableSession(
                            rs.getString("token"),
                            rs.getString("resource_id"),
                            fromJsonSet(rs.getString("rooms")),
                            fromJsonSet(rs.getString("broadcasters")),
                            fromJsonMap(rs.getString("metadata")),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            Instant.ofEpochMilli(rs.getLong("last_seen"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query expired sessions", e);
        }

        if (!expired.isEmpty()) {
            try (var delete = connection.prepareStatement(
                    "DELETE FROM durable_sessions WHERE last_seen < ?")) {
                delete.setLong(1, cutoff);
                delete.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete expired sessions", e);
            }
        }

        return expired;
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("SQLite session store closed");
            }
        } catch (SQLException e) {
            logger.warn("Error closing SQLite connection", e);
        }
    }

    // --- JSON serialization ---

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize value", e);
            return "[]";
        }
    }

    private Set<String> fromJsonSet(String json) {
        if (json == null || json.isEmpty()) {
            return Set.of();
        }
        try {
            return mapper.readValue(json, SET_TYPE);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize set", e);
            return Set.of();
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize map", e);
            return Map.of();
        }
    }
}
