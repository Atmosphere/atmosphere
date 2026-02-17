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
package org.atmosphere.plugin.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * A {@link Broadcaster} that uses Redis pub/sub to relay broadcast messages across JVM instances.
 * <p>
 * Messages published locally are sent to a Redis channel named after the Broadcaster ID.
 * Messages received from other nodes are delivered locally via {@link DefaultBroadcaster#broadcast(Object)}.
 * A unique node ID prevents message echo (re-broadcasting messages that originated locally).
 * <p>
 * Configuration (via {@link org.atmosphere.cpr.ApplicationConfig}):
 * <ul>
 *   <li>{@code org.atmosphere.redis.url} — Redis URI (default: {@code redis://localhost:6379})</li>
 *   <li>{@code org.atmosphere.redis.password} — Redis password (optional)</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
public class RedisBroadcaster extends DefaultBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(RedisBroadcaster.class);
    static final String SEPARATOR = "||";

    public static final String REDIS_URL = "org.atmosphere.redis.url";
    public static final String REDIS_PASSWORD = "org.atmosphere.redis.password";

    private final String nodeId = UUID.randomUUID().toString();

    private RedisClient redisClient;
    private StatefulRedisPubSubConnection<String, String> pubConnection;
    private StatefulRedisPubSubConnection<String, String> subConnection;

    public RedisBroadcaster() {
    }

    @Override
    public Broadcaster initialize(String name, URI uri, AtmosphereConfig config) {
        super.initialize(name, uri, config);

        var redisUrl = config.getInitParameter(REDIS_URL, "redis://localhost:6379");
        var password = config.getInitParameter(REDIS_PASSWORD);

        startRedis(redisUrl, password);

        return this;
    }

    /**
     * Start Redis connections and subscribe to the channel. Override in tests to skip real Redis.
     */
    protected void startRedis(String redisUrl, String password) {
        connectToRedis(redisUrl, password);

        subConnection.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                onRedisMessage(message);
            }
        });

        subConnection.sync().subscribe(getID());
        logger.info("RedisBroadcaster {} subscribed to Redis channel '{}'", nodeId, getID());
    }

    /**
     * Connect to Redis. Override in tests to inject mock connections.
     */
    protected void connectToRedis(String redisUrl, String password) {
        var redisUri = RedisURI.create(redisUrl);
        if (password != null && !password.isEmpty()) {
            redisUri.setPassword(password.toCharArray());
        }
        redisClient = RedisClient.create(redisUri);
        pubConnection = redisClient.connectPubSub();
        subConnection = redisClient.connectPubSub();
    }

    @Override
    public Future<Object> broadcast(Object msg) {
        publishToRedis(msg);
        return super.broadcast(msg);
    }

    @Override
    public void releaseExternalResources() {
        try {
            if (subConnection != null && subConnection.isOpen()) {
                subConnection.sync().unsubscribe(getID());
                subConnection.close();
            }
        } catch (Exception e) {
            logger.trace("Error closing Redis subscription connection", e);
        }
        try {
            if (pubConnection != null && pubConnection.isOpen()) {
                pubConnection.close();
            }
        } catch (Exception e) {
            logger.trace("Error closing Redis publish connection", e);
        }
        try {
            if (redisClient != null) {
                redisClient.shutdown();
            }
        } catch (Exception e) {
            logger.trace("Error shutting down Redis client", e);
        }
        logger.info("RedisBroadcaster {} released Redis resources for channel '{}'", nodeId, getID());
    }

    private void publishToRedis(Object msg) {
        try {
            var payload = serializeMessage(msg);
            var envelope = nodeId + SEPARATOR + payload;
            pubConnection.sync().publish(getID(), envelope);
        } catch (Exception e) {
            logger.warn("Failed to publish message to Redis channel '{}'", getID(), e);
        }
    }

    void onRedisMessage(String envelope) {
        var separatorIndex = envelope.indexOf(SEPARATOR);
        if (separatorIndex < 0) {
            logger.warn("Malformed Redis message received on channel '{}': no separator", getID());
            return;
        }

        var senderId = envelope.substring(0, separatorIndex);
        if (nodeId.equals(senderId)) {
            return;
        }

        var payload = envelope.substring(separatorIndex + SEPARATOR.length());
        logger.trace("Received remote broadcast on channel '{}' from node {}", getID(), senderId);
        super.broadcast(deserializeMessage(payload));
    }

    String getNodeId() {
        return nodeId;
    }

    String serializeMessage(Object msg) {
        return switch (msg) {
            case String s -> s;
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> msg.toString();
        };
    }

    Object deserializeMessage(String payload) {
        return payload;
    }
}
