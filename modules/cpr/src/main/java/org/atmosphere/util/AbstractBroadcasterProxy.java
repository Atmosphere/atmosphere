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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.BroadcasterFuture;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Entry;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.Future;


/**
 * Abstract {@link org.atmosphere.cpr.Broadcaster} that delegates the internal processing to a proxy.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class AbstractBroadcasterProxy extends DefaultBroadcaster {
    private static final Logger logger = LoggerFactory.getLogger(AbstractBroadcasterProxy.class);

    private Method jerseyBroadcast;

    public AbstractBroadcasterProxy(String id, URI uri, AtmosphereConfig config) {
        super(id, uri, config);
    }

    /**
     * Implement this method to broadcast message received from an external source like JGroups, Redis, etc.
     */
    abstract public void incomingBroadcast();

    /**
     * Implement this method to broadcast message to external source like JGroups, Redis, etc.
     *
     * @param message outgoing message
     */
    abstract public void outgoingBroadcast(Object message);

    /**
     * {@inheritDoc}
     */
    @Override
    protected Runnable getBroadcastHandler() {
        return new Runnable() {
            public void run() {
                try {
                    incomingBroadcast();
                } catch (Throwable t) {
                    logger.warn("incomingBroadcast Exception. Broadcaster will be broken unless reconfigured", t);
                    destroy();
                    return;
                }
            }
        };
    }

    protected void reconfigure() {
        if (!started.get()) {
            return;
        }

        logger.debug("Reconfiguring Broadcaster {}", getID());
        spawnReactor();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void invokeOnStateChange(final AtmosphereResource r, final AtmosphereResourceEvent e) {
        if (r.getRequest() instanceof HttpServletRequest) {
            if (r.getRequest().getAttribute(FrameworkConfig.CONTAINER_RESPONSE) != null) {
                try {
                    if (jerseyBroadcast == null) {
                        Class jerseyBroadcasterUtil = Class.forName("org.atmosphere.jersey.util.JerseyBroadcasterUtil");
                        jerseyBroadcast = jerseyBroadcasterUtil.getMethod("broadcast", new Class[]{AtmosphereResource.class, AtmosphereResourceEvent.class});
                    }
                    jerseyBroadcast.invoke(null, new Object[]{r, e});
                } catch (Throwable t) {
                    super.invokeOnStateChange(r, e);
                }
            } else {
                super.invokeOnStateChange(r, e);
            }
        }
    }

    protected void broadcastReceivedMessage(Object message) {
        try {
            Object newMsg = filter(message);
            // if newSgw == null, that means the message has been filtered.
            if (newMsg != null) {
                push(new Entry(newMsg, new BroadcasterFuture<Object>(newMsg, this), message));
            }
        } catch (Throwable t) {
            logger.error("failed to push message: " + message, t);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> broadcast(Object msg) {
        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used {}", getID());
            return null;
        }

        start();

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(msg, this);
        try {
            outgoingBroadcast(msg);
        } finally {
            futureDone(f);
        }
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> broadcast(Object msg, AtmosphereResource r) {
        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used {}", getID());
            return null;
        }

        start();

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(msg, this);
        try {
            outgoingBroadcast(msg);
        } finally {
            futureDone(f);
        }
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Object> broadcast(Object msg, Set<AtmosphereResource> subset) {
        if (destroyed.get()) {
            logger.warn("This Broadcaster has been destroyed and cannot be used {}", getID());
            return null;
        }

        start();

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(msg, this);
        try {
            outgoingBroadcast(msg);
        } finally {
            futureDone(f);
        }
        return f;
    }
}
