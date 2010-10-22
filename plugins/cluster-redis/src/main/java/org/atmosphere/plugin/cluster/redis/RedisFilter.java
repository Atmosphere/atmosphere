/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.plugin.cluster.redis;

import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.util.LoggerUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Support for Redis
 *
 * @author Jeanfrancois Arcand
 */
public class RedisFilter implements ClusterBroadcastFilter {
    static final Logger logger = LoggerUtils.getLogger();
    private Broadcaster bc;
    private static final AtomicInteger count = new AtomicInteger();
    private final Jedis jedisSubscriber;
    private final Jedis jedisPublisher;
    private final ExecutorService listener;
    private String address;
    private final ConcurrentLinkedQueue<String> receivedMessages;

    public RedisFilter() {
        this(null);
    }

    public RedisFilter(Broadcaster bc) {
        this(bc, "atmosphere-framework");
    }

    public RedisFilter(Broadcaster bc, String containerName) {
        this(bc, containerName, "atmosphere");
    }

    public RedisFilter(Broadcaster bc, String containerName, String address) {
        this.listener = Executors.newSingleThreadExecutor();

        this.receivedMessages = new ConcurrentLinkedQueue();

        this.bc = bc;
        this.jedisSubscriber = new Jedis(containerName, 6379, 500);
        try {
            this.jedisSubscriber.connect();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "", e);
        }
        this.jedisSubscriber.auth("atmosphere");
        this.jedisSubscriber.flushAll();

        this.jedisPublisher = new Jedis(containerName, 6379, 500);
        try {
            this.jedisPublisher.connect();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "", e);
        }
        this.jedisPublisher.auth("atmosphere");
        this.jedisPublisher.flushAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        logger.log(Level.INFO, "Starting Atmosphere Redis Clustering support");
        final Broadcaster broadcaster = this.bc;
        this.listener.submit(new Runnable() {
            public void run() {
                RedisFilter.this.jedisSubscriber.subscribe(new JedisPubSub() {
                    public void onMessage(String channel, String message) {
                        RedisFilter.this.receivedMessages.offer(message);
                        broadcaster.broadcast(message);
                    }

                    public void onSubscribe(String channel, int subscribedChannels) {
                    }

                    public void onUnsubscribe(String channel, int subscribedChannels) {
                    }

                    public void onPSubscribe(String pattern, int subscribedChannels) {
                    }

                    public void onPUnsubscribe(String pattern, int subscribedChannels) {
                    }

                    public void onPMessage(String pattern, String channel, String message) {
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
        this.listener.shutdownNow();
        try {
            this.jedisPublisher.disconnect();
            this.jedisSubscriber.disconnect();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BroadcastFilter.BroadcastAction filter(Object o) {
        if (!(this.receivedMessages.remove(o.toString()))) {
            this.jedisPublisher.publish(this.bc.getID(), o.toString());
        }
        return new BroadcastFilter.BroadcastAction(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster getBroadcaster() {
        return this.bc;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBroadcaster(Broadcaster bc) {
        this.bc = bc;
    }
}
