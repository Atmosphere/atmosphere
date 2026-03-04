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
package org.atmosphere.session.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.atmosphere.ai.ConversationPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * {@link ConversationPersistence} backed by Redis, using Lettuce.
 *
 * <p>Shares the same Redis infrastructure as {@link RedisSessionStore}.
 * Conversations are stored under the key prefix {@code atmosphere:conversation:}
 * with a configurable TTL.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Standalone
 * var persistence = new RedisConversationPersistence("redis://localhost:6379");
 *
 * // Shared connection with RedisSessionStore
 * var persistence = new RedisConversationPersistence(existingConnection, Duration.ofHours(24));
 *
 * // Wire into PersistentConversationMemory
 * var memory = new PersistentConversationMemory(persistence, 20);
 * }</pre>
 */
public class RedisConversationPersistence implements ConversationPersistence {

    private static final Logger logger = LoggerFactory.getLogger(RedisConversationPersistence.class);
    private static final String KEY_PREFIX = "atmosphere:conversation:";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final Duration ttl;

    /**
     * Create a persistence layer connected to the given Redis URI.
     */
    public RedisConversationPersistence(String redisUri) {
        this(redisUri, Duration.ofHours(24));
    }

    /**
     * Create a persistence layer with a custom TTL.
     */
    public RedisConversationPersistence(String redisUri, Duration ttl) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.commands = connection.sync();
        this.ttl = ttl;
        logger.info("Redis conversation persistence connected");
    }

    /**
     * Create a persistence layer using an existing Lettuce connection
     * (for sharing with {@link RedisSessionStore} or {@code atmosphere-redis} clustering).
     */
    public RedisConversationPersistence(StatefulRedisConnection<String, String> connection, Duration ttl) {
        this.client = null;
        this.connection = connection;
        this.commands = connection.sync();
        this.ttl = ttl;
    }

    @Override
    public Optional<String> load(String conversationId) {
        try {
            var json = commands.get(KEY_PREFIX + conversationId);
            return Optional.ofNullable(json);
        } catch (Exception e) {
            logger.error("Failed to load conversation {}", conversationId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(String conversationId, String data) {
        try {
            commands.setex(KEY_PREFIX + conversationId, ttl.toSeconds(), data);
        } catch (Exception e) {
            logger.error("Failed to save conversation {}", conversationId, e);
        }
    }

    @Override
    public void remove(String conversationId) {
        try {
            commands.del(KEY_PREFIX + conversationId);
        } catch (Exception e) {
            logger.error("Failed to remove conversation {}", conversationId, e);
        }
    }

    /**
     * Close the connection. Only closes if this instance owns the connection
     * (i.e., was created with a Redis URI, not a shared connection).
     */
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
