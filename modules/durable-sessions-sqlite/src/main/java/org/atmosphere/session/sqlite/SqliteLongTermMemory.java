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

import org.atmosphere.ai.memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link LongTermMemory} backed by an embedded SQLite database. Facts are
 * stored in the {@code ai_user_facts} table, keyed by {@code user_id} with an
 * insertion-ordered {@code id} column for recency. Can share a connection
 * with {@link SqliteSessionStore} or {@link SqliteConversationPersistence}
 * using the same database file.
 *
 * <p>Per-user cap is enforced on every {@code saveFact} by deleting rows
 * outside the most recent {@code maxFacts} window for that user.</p>
 */
public class SqliteLongTermMemory implements LongTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(SqliteLongTermMemory.class);
    private static final int DEFAULT_MAX_FACTS = 100;

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private final boolean ownsConnection;
    private final int maxFacts;

    /**
     * Create with the default database file ({@code atmosphere-facts.db}) and
     * a per-user cap of 100 facts.
     */
    public SqliteLongTermMemory() {
        this(Path.of("atmosphere-facts.db"), DEFAULT_MAX_FACTS);
    }

    /**
     * Create with a specific database file path and per-user cap.
     */
    public SqliteLongTermMemory(Path dbPath, int maxFacts) {
        this(toJdbcUrl(dbPath), true, maxFacts);
    }

    /**
     * Create using an existing JDBC connection (for sharing with
     * {@link SqliteSessionStore} using the same database file).
     */
    public SqliteLongTermMemory(Connection connection, int maxFacts) {
        this.connection = connection;
        this.ownsConnection = false;
        this.maxFacts = maxFacts;
        try {
            createTable();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create user_facts table", e);
        }
    }

    /**
     * Create an in-memory long-term memory (for testing) with a per-user cap.
     */
    public static SqliteLongTermMemory inMemory(int maxFacts) {
        return new SqliteLongTermMemory("jdbc:sqlite::memory:", true, maxFacts);
    }

    /**
     * Create an in-memory long-term memory (for testing) with the default cap.
     */
    public static SqliteLongTermMemory inMemory() {
        return inMemory(DEFAULT_MAX_FACTS);
    }

    private SqliteLongTermMemory(String jdbcUrl, boolean ownsConnection, int maxFacts) {
        this.ownsConnection = ownsConnection;
        this.maxFacts = maxFacts;
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            try {
                try (var stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                }
                createTable();
            } catch (SQLException e) {
                try { connection.close(); } catch (SQLException ex) { e.addSuppressed(ex); }
                throw e;
            }
            logger.info("SQLite long-term memory initialized: {} (cap {})", jdbcUrl, maxFacts);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite long-term memory", e);
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

    private void createTable() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ai_user_facts (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id     TEXT NOT NULL,
                        fact_text   TEXT NOT NULL,
                        created_at  INTEGER NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_user_facts_user_id_created
                        ON ai_user_facts (user_id, id DESC)
                    """);
        }
    }

    @Override
    public void saveFact(String userId, String fact) {
        lock.lock();
        try {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO ai_user_facts (user_id, fact_text, created_at)
                    VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, userId);
                insert.setString(2, fact);
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            evictOverflow(userId);
        } catch (SQLException e) {
            logger.error("Failed to save fact for user {}", userId, e);
        } finally {
            lock.unlock();
        }
    }

    private void evictOverflow(String userId) throws SQLException {
        try (var stmt = connection.prepareStatement("""
                DELETE FROM ai_user_facts
                 WHERE user_id = ?
                   AND id NOT IN (
                       SELECT id FROM ai_user_facts
                        WHERE user_id = ?
                        ORDER BY id DESC
                        LIMIT ?
                   )
                """)) {
            stmt.setString(1, userId);
            stmt.setString(2, userId);
            stmt.setInt(3, maxFacts);
            stmt.executeUpdate();
        }
    }

    @Override
    public List<String> getFacts(String userId, int max) {
        lock.lock();
        var facts = new ArrayList<String>();
        try (var stmt = connection.prepareStatement("""
                SELECT fact_text FROM (
                    SELECT id, fact_text FROM ai_user_facts
                     WHERE user_id = ?
                     ORDER BY id DESC
                     LIMIT ?
                ) ORDER BY id ASC
                """)) {
            stmt.setString(1, userId);
            stmt.setInt(2, max);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    facts.add(rs.getString("fact_text"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load facts for user {}", userId, e);
            return List.of();
        } finally {
            lock.unlock();
        }
        return Collections.unmodifiableList(facts);
    }

    @Override
    public void clear(String userId) {
        lock.lock();
        try (var stmt = connection.prepareStatement(
                "DELETE FROM ai_user_facts WHERE user_id = ?")) {
            stmt.setString(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to clear facts for user {}", userId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Close the connection if this instance owns it.
     */
    public void close() {
        if (ownsConnection) {
            lock.lock();
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    logger.info("SQLite long-term memory closed");
                }
            } catch (SQLException e) {
                logger.warn("Error closing SQLite connection", e);
            } finally {
                lock.unlock();
            }
        }
    }
}
