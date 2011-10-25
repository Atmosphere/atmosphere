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

package org.atmosphere.cpr;


import org.atmosphere.di.InjectorProvider;
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY;
import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER;

/**
 * This class is responsible for creating {@link Broadcaster} instance. You can also add and remove {@link Broadcaster}
 * and lookup using {@link BroadcasterFactory#getDefault()} ()}
 * from any Classes loaded using the same class loader.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultBroadcasterFactory extends BroadcasterFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcasterFactory.class);

    private final ConcurrentHashMap<Object, Broadcaster> store
            = new ConcurrentHashMap<Object, Broadcaster>();

    private final Class<? extends Broadcaster> clazz;

    private BroadcasterLifeCyclePolicy policy =
            new BroadcasterLifeCyclePolicy.Builder().policy(NEVER).build();

    protected DefaultBroadcasterFactory(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereServlet.AtmosphereConfig c) {
        this.clazz = clazz;
        config = c;
        if (factory == null) {
            this.factory = this;
        }
        configure(broadcasterLifeCyclePolicy);
    }

    private void configure(String broadcasterLifeCyclePolicy) {

        int maxIdleTime = 5 * 60 * 100;
        String idleTime = config.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY_IDLETIME);
        if (idleTime != null) {
            maxIdleTime = Integer.parseInt(idleTime);
        }

        if (EMPTY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(EMPTY).build();
        } else if (EMPTY_DESTROY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(EMPTY_DESTROY).build();
        } else if (IDLE.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE).idleTimeInMS(maxIdleTime).build();
        } else if (IDLE_DESTROY.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(IDLE_DESTROY).idleTimeInMS(maxIdleTime).build();
        } else if (NEVER.name().equalsIgnoreCase(broadcasterLifeCyclePolicy)) {
            policy = new BroadcasterLifeCyclePolicy.Builder().policy(NEVER).build();
        } else {
            logger.warn("Unsupported BroadcasterLifeCyclePolicy policy {}", broadcasterLifeCyclePolicy);
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized final Broadcaster get() {
        return get(clazz.getSimpleName() + "-" + UUID.randomUUID());
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster get(Object id) {

        Broadcaster b = store.get(id);
        if (b != null) {
            throw new IllegalStateException("Broadcaster already existing. Use BroadcasterFactory.lookup instead");
        }

        synchronized (id) {
            try {
                b = clazz.getConstructor(String.class, AtmosphereServlet.AtmosphereConfig.class).newInstance(id.toString(), config);
            } catch (Throwable t) {
                throw new BroadcasterCreationException(t);
            }
            InjectorProvider.getInjector().inject(b);
            b.setBroadcasterConfig(new BroadcasterConfig(AtmosphereServlet.broadcasterFilters, config));
            b.setBroadcasterLifeCyclePolicy(policy);

            if (DefaultBroadcaster.class.isAssignableFrom(clazz)) {
                DefaultBroadcaster.class.cast(b).start();
            }

            store.put(b.getID(), b);
        }
        return b;
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster get(Class<? extends Broadcaster> c, Object id) {

        if (id == null) throw new NullPointerException("id is null");
        if (c == null) throw new NullPointerException("Class is null");

        if (getBroadcaster(id) != null)
            throw new IllegalStateException("Broadcaster already existing. Use BroadcasterFactory.lookup instead");

        Broadcaster b = null;

        synchronized (id) {
            try {
                b = c.getConstructor(String.class, AtmosphereServlet.AtmosphereConfig.class).newInstance(id.toString(), config);
            } catch (Throwable t) {
                throw new BroadcasterCreationException(t);
            }
            InjectorProvider.getInjector().inject(b);
            b.setBroadcasterConfig(new BroadcasterConfig(AtmosphereServlet.broadcasterFilters, config));
            b.setBroadcasterLifeCyclePolicy(policy);

            if (DefaultBroadcaster.class.isAssignableFrom(clazz)) {
                DefaultBroadcaster.class.cast(b).start();
            }

            store.put(id, b);
        }
        return b;
    }

    /**
     * Return a {@link Broadcaster} based on its name.
     *
     * @param name The unique ID
     * @return a {@link Broadcaster}, or null
     */
    private Broadcaster getBroadcaster(Object name) {
        return store.get(name);
    }

    /**
     * {@inheritDoc}
     */
    public boolean add(Broadcaster b, Object id) {
        return (store.put(id, b) == null);
    }

    /**
     * {@inheritDoc}
     */
    public boolean remove(Broadcaster b, Object id) {
        logger.debug("Removing Broadcaster {} which internal ref is {} ", id, b.getID());
        return store.remove(id) != null ? true : (store.remove(b.getID()) != null);
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
        return lookup(clazz, id, false);
    }

    /**
     * {@inheritDoc}
     */
    public final Broadcaster lookup(Object id, boolean createIfNull) {
        return lookup(clazz, id, createIfNull);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster lookup(Class<? extends Broadcaster> c, Object id, boolean createIfNull) {
        Broadcaster b = getBroadcaster(id);
        if (b != null && !c.isAssignableFrom(b.getClass())) {
            String msg = "Invalid lookup class " + c.getName() + ". Cached class is: " + b.getClass().getName();
            logger.debug("{}", msg);
            throw new IllegalStateException(msg);
        }

        if (b == null && createIfNull) {
            b = get(c, id);
        }

        return b;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAllAtmosphereResource(AtmosphereResource<?, ?> r) {
        // Remove inside all Broadcaster as well.
        try {
            if (store.size() > 0) {
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

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Broadcaster> lookupAll() {
        return Collections.unmodifiableCollection(store.values());
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void destroy() {
        Enumeration<Broadcaster> e = store.elements();
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
                logger.trace("destroy", t);
            }
        }

        try {
            if (bc != null) bc.forceDestroy();
        } catch (Throwable t) {
            logger.trace("destroy", t);

        }

        store.clear();
        factory = null;
    }

    /**
     * Build a default {@link BroadcasterFactory} returned when invoking {@link #getDefault()} ()}.
     *
     * @param clazz A class implementing {@link Broadcaster}
     * @param c     An instance of {@link AtmosphereServlet.AtmosphereConfig}
     * @return the default {@link BroadcasterFactory}.
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public static BroadcasterFactory buildAndReplaceDefaultfactory(Class<? extends Broadcaster> clazz, AtmosphereServlet.AtmosphereConfig c)
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
