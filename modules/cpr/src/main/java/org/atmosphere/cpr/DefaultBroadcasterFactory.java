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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * and lookup using {@link BroadcasterFactory#getDefault()} ()} from any classes loaded using the same class loader.
 *
 * @author Jeanfrancois Arcand
 * @author Jason Burgess
 */
public class DefaultBroadcasterFactory extends BroadcasterFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcasterFactory.class);

    private final ConcurrentHashMap<Object, Broadcaster> store = new ConcurrentHashMap<Object, Broadcaster>();

    private final Class<? extends Broadcaster> clazz;

    private BroadcasterLifeCyclePolicy policy =
            new BroadcasterLifeCyclePolicy.Builder().policy(NEVER).build();
    protected Broadcaster.POLICY defaultPolicy = Broadcaster.POLICY.FIFO;
    protected int defaultPolicyInteger = -1;
    private final URI legacyBroadcasterURI = URI.create("http://127.0.0.0");

    protected DefaultBroadcasterFactory(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
        this.clazz = clazz;
        this.factory = this;
        config = c;
        configure(broadcasterLifeCyclePolicy);
    }

    private void configure(String broadcasterLifeCyclePolicy) {

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
    }

    @Override
    public synchronized final Broadcaster get() {
        return get(clazz.getSimpleName() + "-" + UUID.randomUUID());
    }

    @Override
    public final Broadcaster get(Object id) {
        return get(clazz, id);
    }

    @Override
    public final <T extends Broadcaster> T get(Class<T> c, Object id) {

        if (id == null) {
            throw new NullPointerException("id is null");
        }
        if (c == null) {
            throw new NullPointerException("Class is null");
        }

        return lookup(c, id, true, true);
    }

    private <T extends Broadcaster> T createBroadcaster(Class<T> c, Object id) throws BroadcasterCreationException {
        try {
            //T b = c.getConstructor(String.class, AtmosphereConfig.class).newInstance(id.toString(), config);
            T b = config.framework().newClassInstance(c);
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
    public final <T extends Broadcaster> T lookup(Class<T> c, Object id) {
        return lookup(c, id, false);
    }

    @Override
    public final Broadcaster lookup(Object id) {
        return lookup(clazz, id, false);
    }

    @Override
    public final Broadcaster lookup(Object id, boolean createIfNull) {
        return lookup(clazz, id, createIfNull);
    }

    @Override
    public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull) {
        return lookup(c, id, createIfNull, false);
    }

    public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull, boolean unique) {
        synchronized (id) {
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

    @Override
    public void removeAllAtmosphereResource(AtmosphereResource r) {
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
                b.resumeAll();
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
        factory = null;
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
        c.framework().setBroadcasterFactory(factory);
        return factory;
    }

    public static final class BroadcasterCreationException extends RuntimeException {
        public BroadcasterCreationException(Throwable t) {
            super(t);
        }
    }

    @Override
    public BroadcasterFactory addBroadcasterListener(BroadcasterListener l) {
        super.addBroadcasterListener(l);
        for (Broadcaster b : store.values()) {
            b.addBroadcasterListener(l);
        }
        return this;
    }

    @Override
    public BroadcasterFactory removeBroadcasterListener(BroadcasterListener l) {
        super.removeBroadcasterListener(l);
        for (Broadcaster b : store.values()) {
            b.removeBroadcasterListener(l);
        }
        return this;
    }
}
