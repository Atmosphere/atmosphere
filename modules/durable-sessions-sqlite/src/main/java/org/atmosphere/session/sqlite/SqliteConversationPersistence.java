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

import org.atmosphere.ai.ConversationPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ConversationPersistence} backed by an embedded SQLite database.
 * Conversations are stored in the {@code ai_conversations} table. Can share
 * a connection with {@link SqliteSessionStore}.
 */
public class SqliteConversationPersistence implements ConversationPersistence {

    private static final Logger logger = LoggerFactory.getLogger(SqliteConversationPersistence.class);

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private final boolean ownsConnection;

    /**
     * Create with the default database file ({@code atmosphere-conversations.db}).
     */
    public SqliteConversationPersistence() {
        this(Path.of("atmosphere-conversations.db"));
    }

    /**
     * Create with a specific database file path.
     */
    public SqliteConversationPersistence(Path dbPath) {
        this(toJdbcUrl(dbPath), true);
    }

    /**
     * Create using an existing JDBC connection (for sharing with
     * {@link SqliteSessionStore} using the same database file).
     */
    public SqliteConversationPersistence(Connection connection) {
        this.connection = connection;
        this.ownsConnection = false;
        try {
            createTable();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create conversations table", e);
        }
    }

    /**
     * Create an in-memory persistence layer (for testing).
     */
    public static SqliteConversationPersistence inMemory() {
        return new SqliteConversationPersistence("jdbc:sqlite::memory:", true);
    }

    private SqliteConversationPersistence(String jdbcUrl, boolean ownsConnection) {
        this.ownsConnection = ownsConnection;
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            try (var stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            createTable();
            logger.info("SQLite conversation persistence initialized: {}", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite conversation persistence", e);
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
                    CREATE TABLE IF NOT EXISTS ai_conversations (
                        conversation_id TEXT PRIMARY KEY,
                        data            TEXT NOT NULL,
                        updated_at      INTEGER NOT NULL
                    )
                    """);
        }
    }

    @Override
    public Optional<String> load(String conversationId) {
        lock.lock();
        try (var stmt = connection.prepareStatement(
                "SELECT data FROM ai_conversations WHERE conversation_id = ?")) {
            stmt.setString(1, conversationId);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("data"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load conversation {}", conversationId, e);
        } finally {
            lock.unlock();
        }
        return Optional.empty();
    }

    @Override
    public void save(String conversationId, String data) {
        lock.lock();
        try (var stmt = connection.prepareStatement("""
                INSERT OR REPLACE INTO ai_conversations (conversation_id, data, updated_at)
                VALUES (?, ?, ?)
                """)) {
            stmt.setString(1, conversationId);
            stmt.setString(2, data);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save conversation {}", conversationId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(String conversationId) {
        lock.lock();
        try (var stmt = connection.prepareStatement(
                "DELETE FROM ai_conversations WHERE conversation_id = ?")) {
            stmt.setString(1, conversationId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove conversation {}", conversationId, e);
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
                    logger.info("SQLite conversation persistence closed");
                }
            } catch (SQLException e) {
                logger.warn("Error closing SQLite connection", e);
            } finally {
                lock.unlock();
            }
        }
    }
}
