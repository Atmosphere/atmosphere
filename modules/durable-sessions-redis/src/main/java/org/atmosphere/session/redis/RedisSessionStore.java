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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.atmosphere.session.DurableSession;
import org.atmosphere.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SessionStore} backed by Redis, using Lettuce.
 *
 * <p>Sessions are stored as JSON hashes with a TTL, making them accessible
 * across all nodes in a cluster. Perfect for multi-node production deployments
 * and Kubernetes environments.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var store = new RedisSessionStore("redis://localhost:6379");
 * var store = new RedisSessionStore("redis://password@redis-host:6379/0");
 * }</pre>
 */
public class RedisSessionStore implements SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisSessionStore.class);
    private static final String KEY_PREFIX = "atmosphere:session:";
    private static final String INDEX_KEY = "atmosphere:sessions";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ObjectMapper mapper;
    private final Duration defaultTtl;

    /**
     * Create a store connected to the given Redis URI.
     *
     * @param redisUri Redis URI (e.g. {@code redis://localhost:6379})
     */
    public RedisSessionStore(String redisUri) {
        this(redisUri, Duration.ofHours(24));
    }

    /**
     * Create a store with a custom default TTL.
     */
    public RedisSessionStore(String redisUri, Duration defaultTtl) {
        this.client = RedisClient.create(redisUri);
        try {
            this.connection = client.connect();
        } catch (Exception e) {
            client.shutdown();
            throw e;
        }
        this.commands = connection.sync();
        this.mapper = new ObjectMapper();
        this.defaultTtl = defaultTtl;
        logger.info("Redis session store connected to {}", maskPassword(redisUri));
    }

    /**
     * Create a store using an existing Lettuce connection (for sharing
     * with {@code atmosphere-redis} clustering).
     */
    public RedisSessionStore(StatefulRedisConnection<String, String> connection, Duration defaultTtl) {
        this.client = null;
        this.connection = connection;
        this.commands = connection.sync();
        this.mapper = new ObjectMapper();
        this.defaultTtl = defaultTtl;
    }

    @Override
    public void save(DurableSession session) {
        try {
            var key = KEY_PREFIX + session.token();
            var json = toJson(session);
            commands.setex(key, defaultTtl.toSeconds(), json);
            commands.sadd(INDEX_KEY, session.token());
            logger.debug("Saved session {} to Redis", session.token());
        } catch (Exception e) {
            logger.error("Failed to save session {} to Redis", session.token(), e);
        }
    }

    @Override
    public Optional<DurableSession> restore(String token) {
        try {
            var key = KEY_PREFIX + token;
            var json = commands.get(key);
            if (json == null) {
                commands.srem(INDEX_KEY, token);
                return Optional.empty();
            }
            return Optional.of(fromJson(json));
        } catch (Exception e) {
            logger.error("Failed to restore session {} from Redis", token, e);
            return Optional.empty();
        }
    }

    @Override
    public void remove(String token) {
        commands.del(KEY_PREFIX + token);
        commands.srem(INDEX_KEY, token);
    }

    /**
     * Lua script for atomic touch: GET the value, update lastSeen in the JSON,
     * and SETEX it back — all in a single atomic operation to avoid TOCTOU races.
     *
     * <p>KEYS[1] = session key, ARGV[1] = TTL in seconds, ARGV[2] = new lastSeen millis</p>
     */
    private static final String TOUCH_SCRIPT = """
            local json = redis.call('GET', KEYS[1])
            if not json then
                return nil
            end
            local updated = json:gsub('"lastSeen":%d+', '"lastSeen":' .. ARGV[2])
            redis.call('SETEX', KEYS[1], tonumber(ARGV[1]), updated)
            return 1
            """;

    @Override
    public void touch(String token) {
        var key = KEY_PREFIX + token;
        try {
            commands.eval(TOUCH_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{key},
                    String.valueOf(defaultTtl.toSeconds()),
                    String.valueOf(Instant.now().toEpochMilli()));
        } catch (Exception e) {
            logger.warn("Failed to update lastSeen for session {}", token, e);
        }
    }

    @Override
    public List<DurableSession> removeExpired(Duration ttl) {
        // Redis TTL handles expiration automatically via SETEX.
        // Clean up the index set by removing tokens whose keys no longer exist.
        // The key data is already gone (expired by Redis), so we return stub
        // sessions with the token so callers can perform any cleanup.
        var expired = new ArrayList<DurableSession>();
        var tokens = commands.smembers(INDEX_KEY);
        for (var token : tokens) {
            if (commands.exists(KEY_PREFIX + token) == 0) {
                expired.add(DurableSession.create(token, ""));
                commands.srem(INDEX_KEY, token);
            }
        }
        return expired;
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        logger.info("Redis session store closed");
    }

    // --- JSON serialization ---

    private String toJson(DurableSession session) throws JsonProcessingException {
        var map = Map.of(
                "token", session.token(),
                "resourceId", session.resourceId(),
                "rooms", session.rooms(),
                "broadcasters", session.broadcasters(),
                "metadata", session.metadata(),
                "createdAt", session.createdAt().toEpochMilli(),
                "lastSeen", session.lastSeen().toEpochMilli()
        );
        return mapper.writeValueAsString(map);
    }

    @SuppressWarnings("unchecked")
    private DurableSession fromJson(String json) throws JsonProcessingException {
        var map = mapper.readValue(json, Map.class);
        return new DurableSession(
                (String) map.get("token"),
                (String) map.get("resourceId"),
                Set.copyOf((List<String>) map.get("rooms")),
                Set.copyOf((List<String>) map.get("broadcasters")),
                Map.copyOf((Map<String, String>) map.get("metadata")),
                Instant.ofEpochMilli(((Number) map.get("createdAt")).longValue()),
                Instant.ofEpochMilli(((Number) map.get("lastSeen")).longValue())
        );
    }

    /**
     * Mask the password portion of a Redis URI for safe logging.
     * Turns {@code redis://secret@host:6379} into {@code redis://***@host:6379}.
     */
    private static String maskPassword(String uri) {
        // Redis URIs follow the pattern: redis://[password@]host[:port][/db]
        // or redis://[username:password@]host[:port][/db]
        return uri.replaceFirst("(redis[s]?://)([^@]+)@", "$1***@");
    }
}
