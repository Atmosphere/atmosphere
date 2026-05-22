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
import org.atmosphere.ai.memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link LongTermMemory} backed by Redis via Lettuce. Facts are stored under
 * the key prefix {@code atmosphere:facts:} as a Redis LIST in insertion order
 * (oldest at head, newest at tail). A per-user cap is enforced by
 * {@code LTRIM} on every save. Can share a connection with
 * {@link RedisSessionStore} or {@link RedisConversationPersistence}.
 */
public class RedisLongTermMemory implements LongTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(RedisLongTermMemory.class);
    private static final String KEY_PREFIX = "atmosphere:facts:";
    private static final int DEFAULT_MAX_FACTS = 100;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final int maxFacts;
    private final boolean ownsConnection;

    /**
     * Create a long-term memory connected to the given Redis URI with the
     * default per-user cap of 100 facts.
     */
    public RedisLongTermMemory(String redisUri) {
        this(redisUri, DEFAULT_MAX_FACTS);
    }

    /**
     * Create a long-term memory connected to the given Redis URI with a
     * custom per-user cap.
     */
    public RedisLongTermMemory(String redisUri, int maxFacts) {
        RedisClient c = RedisClient.create(redisUri);
        try {
            this.connection = c.connect();
        } catch (Exception e) {
            try { c.shutdown(); } catch (Exception ex) { /* already failing */ }
            throw e;
        }
        this.client = c;
        this.commands = connection.sync();
        this.maxFacts = maxFacts;
        this.ownsConnection = true;
        logger.info("Redis long-term memory connected (cap {})", maxFacts);
    }

    /**
     * Create a long-term memory using an existing Lettuce connection (for
     * sharing with {@link RedisSessionStore} or
     * {@link RedisConversationPersistence}).
     */
    public RedisLongTermMemory(StatefulRedisConnection<String, String> connection, int maxFacts) {
        this.client = null;
        this.connection = connection;
        this.commands = connection.sync();
        this.maxFacts = maxFacts;
        this.ownsConnection = false;
    }

    private static String key(String userId) {
        return KEY_PREFIX + userId;
    }

    @Override
    public void saveFact(String userId, String fact) {
        try {
            var k = key(userId);
            commands.rpush(k, fact);
            commands.ltrim(k, -maxFacts, -1);
        } catch (Exception e) {
            logger.error("Failed to save fact for user {}", userId, e);
        }
    }

    @Override
    public List<String> getFacts(String userId, int max) {
        try {
            var k = key(userId);
            var facts = commands.lrange(k, -max, -1);
            return facts == null ? List.of() : List.copyOf(facts);
        } catch (Exception e) {
            logger.error("Failed to load facts for user {}", userId, e);
            return List.of();
        }
    }

    @Override
    public void clear(String userId) {
        try {
            commands.del(key(userId));
        } catch (Exception e) {
            logger.error("Failed to clear facts for user {}", userId, e);
        }
    }

    /**
     * Close the connection. Only closes if this instance owns the connection
     * (i.e., was created with a Redis URI, not a shared connection).
     */
    public void close() {
        if (ownsConnection && connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
