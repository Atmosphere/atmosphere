/*
 * Copyright 2012 Jeanfrancois Arcand
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


import org.atmosphere.di.InjectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER;

/**
 * This class is responsible for creating {@link Broadcaster} instance. Use it when your application is
 * used as WebFragment. This class is a hack and will be removed in 1.1.0 for a better fix for
 * https://github.com/Atmosphere/atmosphere/issues/841
 *
 * @author Jeanfrancois Arcand
 */
public class WebFragmentBroadcasterFactory extends BroadcasterFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcasterFactory.class);
    protected final ConcurrentHashMap<String, PerApplicationFactory> instances = new ConcurrentHashMap<String, PerApplicationFactory>();
    private final PerApplicationFactory perApplicationFactory;

    protected WebFragmentBroadcasterFactory(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
        if (factory == null) {
            this.factory = this;
        }
        this.perApplicationFactory = addF(c.framework().uuid, clazz, broadcasterLifeCyclePolicy, c);
        configure(perApplicationFactory);
    }

    protected final static class PerApplicationFactory {
        private final ConcurrentHashMap<Object, Broadcaster> store = new ConcurrentHashMap<Object, Broadcaster>();
        private final Class<? extends Broadcaster> clazz;
        private BroadcasterLifeCyclePolicy policy =
                new BroadcasterLifeCyclePolicy.Builder().policy(NEVER).build();
        private final AtmosphereConfig config;
        public final String broadcasterLifeCyclePolicy;

        public PerApplicationFactory(Class<? extends Broadcaster> clazz, AtmosphereConfig c, String broadcasterLifeCyclePolicy) {
            this.clazz = clazz;
            this.config = c;
            this.broadcasterLifeCyclePolicy = broadcasterLifeCyclePolicy;
        }
    }

    private void configure(PerApplicationFactory perApplicationFactory) {

        int maxIdleTime = 5 * 60 * 1000;
        String idleTime = perApplicationFactory.config.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY_IDLETIME);
        if (idleTime != null) {
            maxIdleTime = Integer.parseInt(idleTime);
        }

        String broadcasterLifeCyclePolicy = perApplicationFactory.broadcasterLifeCyclePolicy;

        if (EMPTY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            perApplicationFactory.policy = new BroadcasterLifeCyclePolicy.Builder().policy(EMPTY).build();
        } else if (EMPTY_DESTROY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            perApplicationFactory.policy = new BroadcasterLifeCyclePolicy.Builder().policy(EMPTY_DESTROY).build();
        } else if (IDLE.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            perApplicationFactory.policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE).idleTimeInMS(maxIdleTime).build();
        } else if (IDLE_DESTROY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            perApplicationFactory.policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE_DESTROY).idleTimeInMS(maxIdleTime).build();
        } else if (IDLE_RESUME.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            perApplicationFactory.policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE_RESUME).idleTimeInMS(maxIdleTime).build();
        } else if (NEVER.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            perApplicationFactory.policy = new BroadcasterLifeCyclePolicy.Builder().policy(NEVER).build();
        } else {
            logger.warn("Unsupported BroadcasterLifeCyclePolicy policy {}", broadcasterLifeCyclePolicy);
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized final Broadcaster get() {
        return get(f().clazz.getSimpleName() + "-" + UUID.randomUUID());
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster get(Object id) {
        return get(f().clazz, id);
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster get(Class<? extends Broadcaster> c, Object id) {

        if (id == null) {
            throw new NullPointerException("id is null");
        }
        if (c == null) {
            throw new NullPointerException("Class is null");
        }

        return lookup(c, id, true, true);
    }

    // ==========================================================================================
    // Watch out, the following code breaks the record of the worse piece of code I ever wrote!
    // Don't get crazy: https://github.com/Atmosphere/atmosphere/issues/841
    protected PerApplicationFactory addF(String uuid, Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
        PerApplicationFactory perApplicationFactory = new PerApplicationFactory(clazz, c, broadcasterLifeCyclePolicy);
        instances.put(uuid, perApplicationFactory);
        return perApplicationFactory;
    }

    private PerApplicationFactory f() {
        if (instances.size() == 1) {
            return perApplicationFactory;
        }

        // Hacky
        String uuid = AtmosphereFramework.__uuid.get();
        if (uuid != null) {
            PerApplicationFactory w2 = instances.get(uuid);
            // Ugly -- this is for unit test support
            if (w2 == null) {
                return perApplicationFactory;
            }
            return w2;
        } else {
            return instances.entrySet().iterator().next().getValue();
        }
    }
    // ==========================================================================================

    private Broadcaster createBroadcaster(Class<? extends Broadcaster> c, Object id) throws BroadcasterCreationException {
        try {
            Broadcaster b = c.getConstructor(String.class, AtmosphereConfig.class).newInstance(id.toString(), f().config);
            InjectorProvider.getInjector().inject(b);

            if (b.getBroadcasterConfig() == null) {
                b.setBroadcasterConfig(new BroadcasterConfig(f().config.framework().broadcasterFilters, f().config));
            }

            b.setBroadcasterLifeCyclePolicy(f().policy);
            if (DefaultBroadcaster.class.isAssignableFrom(f().clazz)) {
                DefaultBroadcaster.class.cast(b).start();
            }

            for (BroadcasterListener l : broadcasterListeners) {
                b.addBroadcasterListener(l);
            }
            notifyOnPostCreate(b);
            return b;
        } catch (Throwable t) {
            logger.error("", t);
            throw new BroadcasterCreationException(t);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean add(Broadcaster b, Object id) {
        return (f().store.put(id, b) == null);
    }

    /**
     * {@inheritDoc}
     */
    public boolean remove(Broadcaster b, Object id) {
        boolean removed = f().store.remove(id, b);
        if (removed) {
            logger.debug("Removing Broadcaster {} factory size now {} ", id, f().store.size());
        }
        return removed;
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster lookup(Class<? extends Broadcaster> c, Object id) {
        return lookup(c, id, false);
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster lookup(Object id) {
        return lookup(f().clazz, id, false);
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster lookup(Object id, boolean createIfNull) {
        return lookup(f().clazz, id, createIfNull);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster lookup(Class<? extends Broadcaster> c, Object id, boolean createIfNull) {
        return lookup(c, id, createIfNull, false);
    }

    public Broadcaster lookup(Class<? extends Broadcaster> c, Object id, boolean createIfNull, boolean unique) {
        synchronized (id) {
            if (unique && f().store.get(id) != null) {
                throw new IllegalStateException("Broadcaster already existing " + id + ". Use BroadcasterFactory.lookup instead");
            }

            Broadcaster b = f().store.get(id);
            if (b != null && !c.isAssignableFrom(b.getClass())) {
                String msg = "Invalid lookup class " + c.getName() + ". Cached class is: " + b.getClass().getName();
                logger.debug(msg);
                throw new IllegalStateException(msg);
            }

            if ((b == null && createIfNull) || (b != null && b.isDestroyed())) {
                if (b != null) {
                    logger.debug("Removing destroyed Broadcaster {}", b.getID());
                    f().store.remove(b.getID(), b);
                }

                Broadcaster nb = f().store.get(id);
                if (nb == null) {
                    nb = createBroadcaster(c, id);
                    f().store.put(id, nb);
                }

                if (nb == null) {
                    logger.debug("Added Broadcaster {} . Factory size: {}", id, f().store.size());
                }

                b = nb;
            }
            return b;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAllAtmosphereResource(AtmosphereResource r) {
        // Remove inside all Broadcaster as well.
        try {
            if (f().store.size() > 0) {
                for (Broadcaster b : lookupAll()) {
                    try {
                        // Prevent deadlock
                        if (b.getAtmosphereResources().contains(r)) {
                            b.removeAtmosphereResource(r);
                        }
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
        return f().store.remove(id) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Broadcaster> lookupAll() {
        return Collections.unmodifiableCollection(f().store.values());
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void destroy() {

        AtmosphereConfig config = f().config;
        String s = config.getInitParameter(ApplicationConfig.SHARED);
        if (s != null && s.equalsIgnoreCase("true")) {
            logger.warn("Factory shared, will not be destroyed. That can possibly cause memory leaks if" +
                    "Broadcaster where created. Make sure you destroy them manually.");
            return;
        }

        Enumeration<Broadcaster> e = f().store.elements();
        Broadcaster b;
        // We just need one when shared.
        BroadcasterConfig bc = null;
        while (e.hasMoreElements()) {
            try {
                b = e.nextElement();
                b.resumeAll();
                b.destroy();
                bc = b.getBroadcasterConfig();
            } catch (Throwable t) {
                // Shield us from any bad behaviour
                logger.trace("Destroy", t);
            }
        }

        try {
            if (bc != null) bc.forceDestroy();
        } catch (Throwable t) {
            logger.trace("Destroy", t);

        }

        f().store.clear();
        if (AtmosphereFramework.__uuid.get() != null)
            instances.remove(AtmosphereFramework.__uuid.get());
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

    /**
     * Build a default {@link BroadcasterFactory} returned when invoking {@link #getDefault()} ()}.
     *
     * @param clazz A class implementing {@link Broadcaster}
     * @param c     An instance of {@link AtmosphereConfig}
     * @return the default {@link BroadcasterFactory}.
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public static BroadcasterFactory buildAndReplaceDefaultfactory(Class<? extends Broadcaster> clazz, AtmosphereConfig c)
            throws InstantiationException, IllegalAccessException {

        factory = new DefaultBroadcasterFactory(clazz, "NEVER", c);
        return factory;
    }

    public static final class BroadcasterCreationException extends RuntimeException {
        public BroadcasterCreationException(Throwable t) {
            super(t);
        }
    }
}
