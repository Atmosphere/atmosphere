/*
 * Copyright 2013 Jeanfrancois Arcand
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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;

import java.net.URI;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on Jedis
 *
 * @author Jeanfrancois Arcand
 */
public class RedisBroadcaster extends AbstractBroadcasterProxy {

    private final RedisUtil redisUtil;

    public RedisBroadcaster(String id, AtmosphereConfig config) {
        this(URI.create("http://localhost:6379"), id, config);
    }

    public RedisBroadcaster(URI uri, String id, AtmosphereConfig config) {
        super(id, URI.create("http://localhost:6379"), config);
        this.redisUtil = new RedisUtil(uri, config, new RedisUtil.Callback() {
            @Override
            public String getID() {
                return RedisBroadcaster.this.getID();
            }

            @Override
            public void broadcastReceivedMessage(String message) {
                RedisBroadcaster.this.broadcastReceivedMessage(message);
            }
        });
    }

    public String getAuth() {
        return redisUtil.getAuth();
    }

    public void setAuth(String auth) {
        redisUtil.setAuth(auth);

    }

    public synchronized void setUp() {
        redisUtil.configure();
    }

    @Override
    public synchronized void setID(String id) {
        super.setID(id);
        setUp();
        reconfigure();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        super.destroy();
        redisUtil.destroy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingBroadcast() {
        redisUtil.incomingBroadcast();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {
        redisUtil.outgoingBroadcast(message);
    }
}
