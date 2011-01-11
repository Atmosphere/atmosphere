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
package org.atmosphere.plugin.redis;


import org.atmosphere.util.AbstractBroadcasterProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.net.URI;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on Jedis
 *
 * @author Jeanfrancois Arcand
 */
public class RedisBroadcaster extends AbstractBroadcasterProxy {

    private static final Logger logger = LoggerFactory.getLogger(RedisBroadcaster.class);

    private static final String REDIS_AUTH = RedisBroadcaster.class.getName() + ".authorization";
    private static final String REDIS_SERVER = RedisBroadcaster.class.getName() + ".server";

    private Jedis jedisSubscriber;
    private Jedis jedisPublisher;
    private URI uri;
    private String authToken = "atmosphere";

    public RedisBroadcaster() {
        this(RedisBroadcaster.class.getSimpleName(), URI.create("http://localhost:6379"));
    }

    public RedisBroadcaster(String id) {
        this(id, URI.create("http://localhost:6379"));
    }

    public RedisBroadcaster(URI uri) {
        this(RedisBroadcaster.class.getSimpleName(), uri);
    }

    public RedisBroadcaster(String id, URI uri) {
        super(id);
        this.uri = uri;
    }

    public String getAuth() {
        return authToken;
    }

    public void setAuth(String auth) {
        authToken = auth;
    }

    @Override
    protected void start() {
        super.start();
    }

    public void setUp() {
        if (uri == null) return;

        if (config != null) {
            if (config.getServletConfig().getInitParameter(REDIS_AUTH) != null) {
                authToken = config.getServletConfig().getInitParameter(REDIS_AUTH);
            }

            if (config.getServletConfig().getInitParameter(REDIS_SERVER) != null) {
                uri = URI.create(config.getServletConfig().getInitParameter(REDIS_SERVER));
            }
        }

        jedisSubscriber = new Jedis(uri.getHost(), uri.getPort());
        try {
            jedisSubscriber.connect();
        } catch (IOException e) {
            logger.error("failed to connect subscriber", e);
        }

        jedisSubscriber.auth(authToken);
        jedisSubscriber.flushAll();

        jedisPublisher = new Jedis(uri.getHost(), uri.getPort());
        try {
            jedisPublisher.connect();
        } catch (IOException e) {
            logger.error("failed to connect publisher", e);
        }
        jedisPublisher.auth(authToken);
        jedisPublisher.flushAll();
    }

    @Override
    public void setID(String id) {
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
        disconnectPublisher();
        disconnectSubscriber();
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
        jedisPublisher.publish(getID(), message.toString());
    }

    private void disconnectSubscriber() {
        if (jedisSubscriber != null) {
            try {
                jedisSubscriber.disconnect();
            } catch (IOException e) {
                logger.error("failed to disconnect subscriber", e);
            }
        }
    }

    private void disconnectPublisher() {
        if (jedisPublisher != null) {
            try {
                jedisPublisher.disconnect();
            } catch (IOException e) {
                logger.error("failed to disconnect publisher", e);
            }
        }
    }

}