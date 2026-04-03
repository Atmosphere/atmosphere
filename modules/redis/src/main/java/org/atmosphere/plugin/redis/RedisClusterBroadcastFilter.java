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
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.atmosphere.plugin.redis.RedisBroadcaster.REDIS_PASSWORD;
import static org.atmosphere.plugin.redis.RedisBroadcaster.REDIS_URL;

/**
 * A {@link ClusterBroadcastFilter} that uses Redis pub/sub to relay broadcast messages across JVM instances.
 * <p>
 * This is an alternative to {@link RedisBroadcaster} for users who prefer filter-based clustering
 * instead of a custom Broadcaster class. Add this filter to any standard {@link org.atmosphere.cpr.DefaultBroadcaster}
 * to enable cross-node message delivery via Redis.
 * <p>
 * Configuration (via {@link org.atmosphere.cpr.ApplicationConfig}):
 * <ul>
 *   <li>{@code org.atmosphere.redis.url} — Redis URI (default: {@code redis://localhost:6379})</li>
 *   <li>{@code org.atmosphere.redis.password} — Redis password (optional)</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
public class RedisClusterBroadcastFilter implements ClusterBroadcastFilter {

    private static final Logger logger = LoggerFactory.getLogger(RedisClusterBroadcastFilter.class);
    static final String SEPARATOR = "||";

    private final String nodeId = UUID.randomUUID().toString();

    private volatile RedisClient redisClient;
    private volatile StatefulRedisPubSubConnection<String, String> pubConnection;
    private volatile StatefulRedisPubSubConnection<String, String> subConnection;
    private volatile Broadcaster broadcaster;

    @Override
    public void init(AtmosphereConfig config) {
        var redisUrl = config.getInitParameter(REDIS_URL, "redis://localhost:6379");
        var password = config.getInitParameter(REDIS_PASSWORD);

        connectToRedis(redisUrl, password);

        subConnection.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                onRedisMessage(message);
            }
        });

        logger.info("RedisClusterBroadcastFilter {} initialized", nodeId);
    }

    /**
     * Connect to Redis. Override in tests to inject mock connections.
     */
    @SuppressWarnings("deprecation")
    protected void connectToRedis(String redisUrl, String password) {
        var redisUri = RedisURI.create(redisUrl);
        if (password != null && !password.isEmpty()) {
            redisUri.setPassword(password.toCharArray());
        }
        RedisClient client = RedisClient.create(redisUri);
        try {
            var pub = client.connectPubSub();
            try {
                var sub = client.connectPubSub();
                this.redisClient = client;
                this.pubConnection = pub;
                this.subConnection = sub;
            } catch (Exception e) {
                try { pub.close(); } catch (Exception ex) { logger.trace("Error closing pub connection", ex); }
                throw e;
            }
        } catch (Exception e) {
            try { client.shutdown(); } catch (Exception ex) { logger.trace("Error shutting down client", ex); }
            throw e;
        }
    }

    @Override
    public void destroy() {
        try {
            if (subConnection != null && subConnection.isOpen()) {
                if (broadcaster != null) {
                    subConnection.sync().unsubscribe(broadcaster.getID());
                }
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
        logger.info("RedisClusterBroadcastFilter {} destroyed", nodeId);
    }

    @Override
    public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
        publishToRedis(message);
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }

    @Override
    public void setUri(String name) {
        // URI not used — Redis URL is read from AtmosphereConfig at init()
    }

    @Override
    public void setBroadcaster(Broadcaster bc) {
        this.broadcaster = bc;
        var conn = this.subConnection;
        if (conn != null && conn.isOpen()) {
            conn.sync().subscribe(bc.getID());
            logger.info("RedisClusterBroadcastFilter {} subscribed to channel '{}'", nodeId, bc.getID());
        }
    }

    @Override
    public Broadcaster getBroadcaster() {
        return broadcaster;
    }

    String getNodeId() {
        return nodeId;
    }

    private void publishToRedis(Object msg) {
        var bc = this.broadcaster;
        if (bc == null) return;
        var conn = this.pubConnection;
        if (conn == null || !conn.isOpen()) return;
        try {
            var payload = serializeMessage(msg);
            var envelope = nodeId + SEPARATOR + payload;
            conn.sync().publish(bc.getID(), envelope);
        } catch (Exception e) {
            logger.warn("Failed to publish message to Redis channel '{}'",
                    bc.getID(), e);
        }
    }

    void onRedisMessage(String envelope) {
        var separatorIndex = envelope.indexOf(SEPARATOR);
        if (separatorIndex < 0) {
            logger.warn("Malformed Redis message: no separator");
            return;
        }

        var senderId = envelope.substring(0, separatorIndex);
        if (nodeId.equals(senderId)) {
            return;
        }

        var payload = envelope.substring(separatorIndex + SEPARATOR.length());
        var bc = this.broadcaster;
        if (bc != null) {
            logger.trace("Received remote message from node {} for broadcaster '{}'", senderId, bc.getID());
            bc.broadcast(payload);
        }
    }

    String serializeMessage(Object msg) {
        return switch (msg) {
            case String s -> s;
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> msg.toString();
        };
    }
}
