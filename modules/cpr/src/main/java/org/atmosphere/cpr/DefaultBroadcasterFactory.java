/*
 * Copyright 2015 Async-IO.org
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

package org.atmosphere.cpr;

import org.atmosphere.lifecycle.BroadcasterLifecyclePolicyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_POLICY;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_POLICY_TIMEOUT;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER;

/**
 * This class is responsible for creating {@link Broadcaster} instances. You can also add and remove {@link Broadcaster}
 *
 * @author Jeanfrancois Arcand
 * @author Jason Burgess
 */
public class DefaultBroadcasterFactory implements BroadcasterFactory {
    protected final ConcurrentLinkedQueue<BroadcasterListener> broadcasterListeners = new ConcurrentLinkedQueue<BroadcasterListener>();

    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcasterFactory.class);

    protected final ConcurrentHashMap<Object, Broadcaster> store = new ConcurrentHashMap<Object, Broadcaster>();

    protected Class<? extends Broadcaster> clazz;

    protected BroadcasterLifeCyclePolicy policy =
            new BroadcasterLifeCyclePolicy.Builder().policy(NEVER).build();
    protected Broadcaster.POLICY defaultPolicy = Broadcaster.POLICY.FIFO;
    protected int defaultPolicyInteger = -1;

    protected AtmosphereConfig config;
    protected final BroadcasterListener lifeCycleListener = new BroadcasterLifecyclePolicyHandler();
    public static final URI legacyBroadcasterURI = URI.create("http://127.0.0.0");

    public DefaultBroadcasterFactory() {
    }

    @Deprecated
    public DefaultBroadcasterFactory(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
        this.clazz = clazz;
        config = c;
        configure(broadcasterLifeCyclePolicy);
    }

    public void configure(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
        this.clazz = clazz;
        config = c;
        configure(broadcasterLifeCyclePolicy);
    }

    protected void configure(String broadcasterLifeCyclePolicy) {

        int maxIdleTime = 5 * 60 * 1000;
        String s = config.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY_IDLETIME);
        if (s != null) {
            maxIdleTime = Integer.parseInt(s);
        }

        s = config.getInitParameter(BROADCASTER_POLICY);
        if (s != null) {
            defaultPolicy = s.equalsIgnoreCase(Broadcaster.POLICY.REJECT.name()) ? Broadcaster.POLICY.REJECT : Broadcaster.POLICY.FIFO;
        }

        s = config.getInitParameter(BROADCASTER_POLICY_TIMEOUT);
        if (s != null) {
            defaultPolicyInteger = Integer.valueOf(s);
        }

        if (EMPTY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(EMPTY).build();
        } else if (EMPTY_DESTROY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(EMPTY_DESTROY).build();
        } else if (IDLE.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE).idleTimeInMS(maxIdleTime).build();
        } else if (IDLE_DESTROY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE_DESTROY).idleTimeInMS(maxIdleTime).build();
        } else if (IDLE_RESUME.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE_RESUME).idleTimeInMS(maxIdleTime).build();
        } else if (NEVER.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(NEVER).build();
        } else {
            logger.warn("Unsupported BroadcasterLifeCyclePolicy policy {}", broadcasterLifeCyclePolicy);
        }

        broadcasterListeners.add(lifeCycleListener);
    }

    @Override
    public synchronized Broadcaster get() {
        return get(clazz.getSimpleName() + "-" + config.uuidProvider().generateUuid());
    }

    @Override
    public Broadcaster get(Object id) {
        return get(clazz, id);
    }

    @Override
    public <T extends Broadcaster> T get(Class<T> c, Object id) {

        if (id == null) {
            throw new NullPointerException("id is null");
        }
        if (c == null) {
            throw new NullPointerException("Class is null");
        }

        return lookup(c, id, true, true);
    }

    protected <T extends Broadcaster> T createBroadcaster(Class<T> c, Object id) throws BroadcasterCreationException {
        try {
            T b = config.framework().newClassInstance(c, c);
            b.initialize(id.toString(), legacyBroadcasterURI, config);
            b.setSuspendPolicy(defaultPolicyInteger, defaultPolicy);

            if (b.getBroadcasterConfig() == null) {
                b.setBroadcasterConfig(new BroadcasterConfig(config.framework().broadcasterFilters, config, id.toString()).init());
            }

            b.setBroadcasterLifeCyclePolicy(policy);
            if (DefaultBroadcaster.class.isAssignableFrom(clazz)) {
                DefaultBroadcaster.class.cast(b).start();
            }

            for (BroadcasterListener l : broadcasterListeners) {
                b.addBroadcasterListener(l);
            }

            logger.trace("Broadcaster {} was created {}", id, b);

            notifyOnPostCreate(b);
            return b;
        } catch (Throwable t) {
            throw new BroadcasterCreationException(t);
        }
    }

    @Override
    public boolean add(Broadcaster b, Object id) {
        return (store.put(id, b) == null);
    }

    @Override
    public boolean remove(Broadcaster b, Object id) {
        boolean removed = store.remove(id, b);
        if (removed && logger.isDebugEnabled()) {
            logger.debug("Removing Broadcaster {} factory size now {} ", id, store.size());
        }
        return removed;
    }

    @Override
    public <T extends Broadcaster> T lookup(Class<T> c, Object id) {
        return lookup(c, id, false);
    }

    @Override
    public Broadcaster lookup(Object id) {
        return lookup(clazz, id, false);
    }

    @Override
    public Broadcaster lookup(Object id, boolean createIfNull) {
        return lookup(clazz, id, createIfNull);
    }

    @Override
    public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull) {
        return lookup(c, id, createIfNull, false);
    }

    public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull, boolean unique) {
        synchronized (c) {
            logger.trace("About to create {}", id);
            if (unique && store.get(id) != null) {
                throw new IllegalStateException("Broadcaster already exists " + id + ". Use BroadcasterFactory.lookup instead");
            }

            T b = (T) store.get(id);
            logger.trace("Looking in the store using {} returned {}", id, b);
            if (b != null && !c.isAssignableFrom(b.getClass())) {
                String msg = "Invalid lookup class " + c.getName() + ". Cached class is: " + b.getClass().getName();
                logger.debug(msg);
                throw new IllegalStateException(msg);
            }

            if ((b == null && createIfNull) || (b != null && b.isDestroyed())) {
                if (b != null) {
                    logger.trace("Removing destroyed Broadcaster {}", b.getID());
                    store.remove(b.getID(), b);
                }

                Broadcaster nb = store.get(id);
                if (nb == null) {
                    nb = createBroadcaster(c, id);
                    store.put(id, nb);
                }

                if (nb == null && logger.isTraceEnabled()) {
                    logger.trace("Added Broadcaster {} . Factory size: {}", id, store.size());
                }

                b = (T) nb;
            }
            return b;
        }
    }

    @Deprecated
    @Override
    public void removeAllAtmosphereResource(AtmosphereResource r) {
        // Remove inside all Broadcaster as well.
        try {
            if (!store.isEmpty()) {
                for (Broadcaster b : lookupAll()) {
                    try {
                        b.removeAtmosphereResource(r);
                    } catch (IllegalStateException ex) {
                        logger.trace(ex.getMessage(), ex);
                    }
                }
            }
        } catch (Exception ex) {
            logger.warn(ex.getMessage(), ex);
        }
    }

    @Override
    public boolean remove(Object id) {
        return store.remove(id) != null;
    }

    @Override
    public Collection<Broadcaster> lookupAll() {
        return Collections.unmodifiableCollection(store.values());
    }

    @Override
    public synchronized void destroy() {
        // Invalid state
        if (config == null) return;

        String s = config.getInitParameter(ApplicationConfig.SHARED);
        if (s != null && s.equalsIgnoreCase("true")) {
            logger.warn("Factory shared, will not be destroyed. This can possibly cause memory leaks if" +
                    "Broadcasters were created. Make sure you destroy them manually.");
            return;
        }

        Enumeration<Broadcaster> e = store.elements();
        Broadcaster b;
        // We just need one when shared.
        BroadcasterConfig bc = null;
        while (e.hasMoreElements()) {
            try {
                b = e.nextElement();
                bc = b.getBroadcasterConfig();
                bc.forceDestroy();
                b.destroy();
            } catch (Throwable t) {
                // Shield us from any bad behaviour
                logger.debug("Destroy", t);
            }
        }
        broadcasterListeners.clear();
        store.clear();
    }

    public void notifyOnPostCreate(Broadcaster b) {
        for (BroadcasterListener l : broadcasterListeners) {
            try {
                l.onPostCreate(b);
            } catch (Exception ex) {
                logger.warn("onPostCreate", ex);
            }
        }
    }

    @Override
    public BroadcasterFactory addBroadcasterListener(BroadcasterListener l) {
        if (!broadcasterListeners.contains(l)) {
            broadcasterListeners.add(l);
        }

        for (Broadcaster b : store.values()) {
            b.addBroadcasterListener(l);
        }
        return this;
    }

    @Override
    public BroadcasterFactory removeBroadcasterListener(BroadcasterListener l) {
        broadcasterListeners.remove(l);
        for (Broadcaster b : store.values()) {
            b.removeBroadcasterListener(l);
        }
        return this;
    }

    /**
     * Return all {@link BroadcasterListener}.
     *
     * @return {@link BroadcasterListener}
     */
    @Override
    public Collection<BroadcasterListener> broadcasterListeners() {
        return broadcasterListeners;
    }
}
