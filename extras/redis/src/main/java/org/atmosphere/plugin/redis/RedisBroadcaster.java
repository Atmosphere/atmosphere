/*
 * Copyright 2011 Jeanfrancois Arcand
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


import org.apache.commons.pool.impl.GenericObjectPool;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisException;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on Jedis
 *
 * @author Jeanfrancois Arcand
 */
public class RedisBroadcaster extends AbstractBroadcasterProxy {

    private static final Logger logger = LoggerFactory.getLogger(RedisBroadcaster.class);

    private static final String REDIS_AUTH = RedisBroadcaster.class.getName() + ".authorization";
    private static final String REDIS_SERVER = RedisBroadcaster.class.getName() + ".server";
    private static final String REDIS_SHARED_POOL = RedisBroadcaster.class.getName() + ".sharedPool";

    private Jedis jedisSubscriber;
    private Jedis jedisPublisher;
    private String authToken = null;

    private boolean sharedPool = false;
    private JedisPool jedisPool;
    private AtomicInteger maxTry = new AtomicInteger();

    public RedisBroadcaster(String id, AtmosphereServlet.AtmosphereConfig config) {
        this(id, URI.create("http://localhost:6379"), config);
    }

    public RedisBroadcaster(String id, URI uri, AtmosphereServlet.AtmosphereConfig config) {
        super(id, uri, config);
    }

    public String getAuth() {
        return authToken;
    }

    public void setAuth(String auth) {
        authToken = auth;
    }

    public synchronized void setUp() {

        if (config.getServletConfig().getInitParameter(REDIS_AUTH) != null) {
            authToken = config.getServletConfig().getInitParameter(REDIS_AUTH);
        }

        if (config.getServletConfig().getInitParameter(REDIS_SERVER) != null) {
            uri = URI.create(config.getServletConfig().getInitParameter(REDIS_SERVER));
        } else if (uri == null) {
            throw new NullPointerException("uri cannot be null");
        }

        if (config.getServletConfig().getInitParameter(REDIS_SHARED_POOL) != null) {
            sharedPool = Boolean.parseBoolean(config.getServletConfig().getInitParameter(REDIS_SHARED_POOL));
        }

        logger.info("{} shared connection pool {}", getClass().getName(), sharedPool);

        if (sharedPool) {
            if (config.properties().get(REDIS_SHARED_POOL) != null) {
                jedisPool = (JedisPool) config.properties().get(REDIS_SHARED_POOL);
            }

            // setup is synchronized, no need to sync here as well.
            if (jedisPool == null) {
                GenericObjectPool.Config gConfig = new GenericObjectPool.Config();
                gConfig.testOnBorrow = true;
                gConfig.testWhileIdle = true;

                jedisPool = new JedisPool(gConfig, uri.getHost(), uri.getPort());

                config.properties().put(REDIS_SHARED_POOL, jedisPool);
            } else {
                if (jedisPublisher != null) {
                    jedisPool.returnResource(jedisPublisher);
                }
                disconnectSubscriber();

            }
        }

        // We use the pool only for publishing
        jedisSubscriber = new Jedis(uri.getHost(), uri.getPort());
        try {
            jedisSubscriber.connect();
            auth(jedisSubscriber);
        } catch (IOException e) {
            logger.error("failed to connect subscriber", e);
            disconnectSubscriber();
        }

        jedisPublisher = sharedPool ? null : new Jedis(uri.getHost(), uri.getPort());
        if (!sharedPool) {
            try {
                jedisPublisher.connect();

                auth(jedisPublisher);
            } catch (IOException e) {
                logger.error("failed to connect publisher", e);
                disconnectPublisher();
            }
        }
    }

    @Override
    public synchronized void setID(String id) {
        super.setID(id);
        disconnectPublisher();
        disconnectSubscriber();
        setUp();
        reconfigure();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        super.destroy();
        try {
            disconnectPublisher();
            disconnectSubscriber();
            if (jedisPool != null) {
                jedisPool.destroy();
            }
        } catch (Throwable t) {
            logger.warn("Jedis error on close", t);
        } finally {
            config.properties().put(REDIS_SHARED_POOL, null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingBroadcast() {
        logger.info("Subscribing to: {}", getID());

        jedisSubscriber.subscribe(new JedisPubSub() {

            public void onMessage(String channel, String message) {
                broadcastReceivedMessage(message);
            }

            public void onSubscribe(String channel, int subscribedChannels) {
                logger.debug("onSubscribe: {}", channel);
            }

            public void onUnsubscribe(String channel, int subscribedChannels) {
                logger.debug("onUnsubscribe: {}", channel);
            }

            public void onPSubscribe(String pattern, int subscribedChannels) {
                logger.debug("onPSubscribe: {}", pattern);
            }

            public void onPUnsubscribe(String pattern, int subscribedChannels) {
                logger.debug("onPUnsubscribe: {}", pattern);
            }

            public void onPMessage(String pattern, String channel, String message) {
                logger.debug("onPMessage: {}", pattern + " " + channel + " " + message);
            }
        }, getID());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {
        // One thread at a time can use a Jedis Connection.
        Object lockingObject = sharedPool ? jedisPool : jedisPublisher;
        boolean valid = true;
        synchronized (lockingObject) {
            try {
                if (sharedPool) {
                    jedisPublisher = jedisPool.getResource();
                    auth(jedisPublisher);
                }
                jedisPublisher.publish(getID(), message.toString());
            } catch (JedisException e) {
                logger.warn("outgoingBroadcast exception", e);
                valid = false;
            } finally {
                if (sharedPool) {
                    if (!valid) {
                        jedisPool.returnBrokenResource(jedisPublisher);
                    } else {
                        maxTry.set(0);
                        jedisPool.returnResource(jedisPublisher);
                    }

                    // This is dangerous to loop.
                    if (!valid && maxTry.getAndIncrement() < 10) {
                        outgoingBroadcast(message);
                    }
                }
            }
        }
    }

    private void auth(Jedis jedis) {
        if (authToken != null) {
            jedis.auth(authToken);
        }
        jedis.flushAll();
    }

    private void disconnectSubscriber() {
        if (jedisSubscriber == null) return;

        synchronized (jedisSubscriber) {
            try {
                jedisSubscriber.disconnect();
            } catch (IOException e) {
                logger.error("failed to disconnect subscriber", e);
            }
        }
    }

    private void disconnectPublisher() {
        if (jedisPublisher == null) return;

        synchronized (jedisPublisher) {
            try {
                jedisPublisher.disconnect();
            } catch (IOException e) {
                logger.error("failed to disconnect publisher", e);
            }
        }
    }

}