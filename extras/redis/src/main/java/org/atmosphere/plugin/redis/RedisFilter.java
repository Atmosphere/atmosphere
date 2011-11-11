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

import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;

import java.net.URI;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Support for Redis
 *
 * @author Jeanfrancois Arcand
 */
public class RedisFilter implements ClusterBroadcastFilter {

    private static final Logger logger = LoggerFactory.getLogger(RedisFilter.class);

    private Broadcaster bc;
    private final ExecutorService listener = Executors.newSingleThreadExecutor();
    private final ConcurrentLinkedQueue<String> receivedMessages = new ConcurrentLinkedQueue<String>();
    private Jedis jedisSubscriber;
    private Jedis jedisPublisher;
    private URI uri;
    private String auth = "atmosphere";

    public RedisFilter() {
        this(RedisFilter.class.getSimpleName(), URI.create("http://localhost:6379"));
    }

    public RedisFilter(String id) {
        this(id, URI.create("http://localhost:6379"));
    }

    public RedisFilter(URI uri) {
        this(RedisFilter.class.getSimpleName(), uri);
    }

    public RedisFilter(String id, URI uri) {
        this.uri = uri;
    }

    public RedisFilter(Broadcaster bc, String address) {

        this.bc = bc;
        uri = URI.create(address);

        if (uri == null) return;

        jedisSubscriber = new Jedis(uri.getHost(), uri.getPort());
        try {
            jedisSubscriber.connect();
            auth(jedisSubscriber);
        } catch (JedisException e) {
            logger.error("failed to connect to subscriber: {}", jedisSubscriber, e);
        }

        jedisPublisher = new Jedis(uri.getHost(), uri.getPort());
        try {
            jedisPublisher.connect();
            auth(jedisPublisher);
        } catch (JedisException e) {
            logger.error("failed to connect to publisher: {}", jedisPublisher, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUri(String address) {
        uri = URI.create(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        logger.info("Starting Atmosphere Redis Clustering support");

        final Broadcaster broadcaster = bc;
        listener.submit(new Runnable() {
            public void run() {
                jedisSubscriber.subscribe(new JedisPubSub() {
                    public void onMessage(String channel, String message) {
                        receivedMessages.offer(message);
                        broadcaster.broadcast(message);
                    }

                    public void onSubscribe(String channel, int subscribedChannels) {
                        logger.debug("onSubscribe(): channel: {}", channel);
                    }

                    public void onUnsubscribe(String channel, int subscribedChannels) {
                        logger.debug("onUnsubscribe(): channel: {}", channel);
                    }

                    public void onPSubscribe(String pattern, int subscribedChannels) {
                        logger.debug("onPSubscribe(): pattern: {}", pattern);
                    }

                    public void onPUnsubscribe(String pattern, int subscribedChannels) {
                        logger.debug("onPUnsubscribe(): pattern: {}", pattern);
                    }

                    public void onPMessage(String pattern, String channel, String message) {
                        logger.debug("onPMessage: pattern: {}, channel: {}, message: {}",
                                new Object[]{pattern, channel, message});

                    }
                }, bc.getID());
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        listener.shutdownNow();
        try {
            jedisPublisher.disconnect();
            jedisSubscriber.disconnect();
        } catch (JedisException e) {
            logger.error("failure encountered during destroy", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BroadcastFilter.BroadcastAction filter(Object originalMessage, Object o) {
        String contents = originalMessage.toString();

        if (!(receivedMessages.remove(contents))) {
            jedisPublisher.publish(bc.getID(), contents);
        }
        return new BroadcastFilter.BroadcastAction(BroadcastAction.ACTION.CONTINUE, o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster getBroadcaster() {
        return bc;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBroadcaster(Broadcaster bc) {
        this.bc = bc;
    }

    private void auth(Jedis jedis) {
        if (auth != null) {
            jedis.auth(auth);
        }
        jedis.flushAll();
    }
}
