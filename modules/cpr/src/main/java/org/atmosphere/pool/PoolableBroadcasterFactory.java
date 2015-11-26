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

package org.atmosphere.pool;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;

import static org.atmosphere.cpr.ApplicationConfig.POOLEABLE_PROVIDER;
import static org.atmosphere.cpr.ApplicationConfig.SUPPORT_TRACKED_BROADCASTER;

/**
 * This class uses a {@link org.atmosphere.pool.PoolableProvider} to retrieve instance of {@link Broadcaster}. This class
 * doesn't validate the id of the Broadcaster [{@link Broadcaster#setID(String)}] and can return a Broadcaster
 * with was already created under that name. Set {@link #trackPooledBroadcaster(boolean)} or {@link org.atmosphere.cpr.ApplicationConfig#SUPPORT_TRACKED_BROADCASTER} to true to track duplication but
 * be aware it can significantly reduce performance. Use the {@link org.atmosphere.cpr.DefaultBroadcasterFactory} is recommended
 * under that scenario.
 * <p></p>
 * By default, this factory doesn't keep trace of created Broadcasters hence a new pooled Broadcaster will always
 * be returned unless {@link #trackPooledBroadcaster(boolean)} or {@link org.atmosphere.cpr.ApplicationConfig#SUPPORT_TRACKED_BROADCASTER}
 * is set to true.
 * <p/>
 * This Factory has been designed for application.
 * </p>
 * This factory is usefull when an application needs a short-lived {@link Broadcaster}.
 *
 * @author Jeanfrancois Arcand
 */
public class PoolableBroadcasterFactory extends DefaultBroadcasterFactory {

    private static final Logger logger = LoggerFactory.getLogger(PoolableBroadcasterFactory.class);
    private PoolableProvider<? extends Broadcaster,?> poolableProvider;
    private final static String POOLED_ID = "POOLED";
    private final static Collection emptyCollection = Collections.emptySet();
    private boolean trackPooledBroadcaster;

    public PoolableBroadcasterFactory() {
        super();
    }

    // Testing
    @Deprecated
    public PoolableBroadcasterFactory(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
        super(clazz, broadcasterLifeCyclePolicy, c);
    }

    protected void configure(String broadcasterLifeCyclePolicy) {
        super.configure(broadcasterLifeCyclePolicy);

        String poolableProviderClass = config.getInitParameter(POOLEABLE_PROVIDER, UnboundedApachePoolableProvider.class.getName());
        try {
            poolableProvider = config.framework().newClassInstance(PoolableProvider.class,
                    (Class<PoolableProvider>) IOUtils.loadClass(PoolableProvider.class, poolableProviderClass));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        poolableProvider.configure(config);

        trackPooledBroadcaster = config.getInitParameter(SUPPORT_TRACKED_BROADCASTER, false);
    }

    @Override
    public Broadcaster get() {
        return get(POOLED_ID);
    }

    @Override
    public boolean add(Broadcaster b, Object id) {
        if (trackPooledBroadcaster) {
            super.add(b, id);
        }
        poolableProvider.returnBroadcaster(b);
        return true;
    }

    @Override
    public boolean remove(Broadcaster b, Object id) {
        if (trackPooledBroadcaster) {
            return store.remove(b) != null;
        }
        poolableProvider.returnBroadcaster(b);
        return false;
    }

    @Override
    public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull, boolean unique) {
        Broadcaster broadcaster = null;
        if (trackPooledBroadcaster) {
            broadcaster = store.get(id);
        }

        if (broadcaster == null) {
            broadcaster = poolableProvider.borrowBroadcaster(id);
        }
        return (T) broadcaster;
    }

    @Override
    public void removeAllAtmosphereResource(AtmosphereResource r) {
        logger.debug("Operation no supported");
        if (trackPooledBroadcaster) {
            super.removeAllAtmosphereResource(r);
        }
    }

    @Override
    public boolean remove(Object id) {
        if (trackPooledBroadcaster) {
            super.remove(id);
        } else {
            logger.debug("Operation no supported");
        }
        return false;
    }

    @Override
    public Collection<Broadcaster> lookupAll() {
        if (trackPooledBroadcaster) {
            super.lookupAll();
        }
        return emptyCollection;
    }

    public Broadcaster createBroadcaster() {
        return createBroadcaster(clazz, "POOLED");
    }

    /**
     * Set to true to enable tracking of {@link org.atmosphere.cpr.Broadcaster#getID()} duplication. Enabling this
     * feature will significantly reduce the performance of the {@link org.atmosphere.pool.PoolableProvider}. Use the
     * {@link org.atmosphere.cpr.DefaultBroadcasterFactory} if you need to track's duplication.
     *
     * @param trackPooledBroadcaster
     * @return
     */
    public PoolableBroadcasterFactory trackPooledBroadcaster(boolean trackPooledBroadcaster) {
        this.trackPooledBroadcaster = trackPooledBroadcaster;
        return this;
    }

    /**
     * Return true is {@link Broadcaster} instance are tracked, e.g stored in a Collection for duplicate id.
     *
     * @return {@link Broadcaster} instance are tracked, e.g stored in a Collection for duplicate id.
     */
    public boolean trackPooledBroadcaster() {
        return trackPooledBroadcaster;
    }

    /**
     * The current {@link org.atmosphere.pool.PoolableProvider}
     *
     * @return current {@link org.atmosphere.pool.PoolableProvider}
     */
    public PoolableProvider<? extends Broadcaster, ?> poolableProvider() {
        return poolableProvider;
    }

    /**
     * Set the implementation of {@link org.atmosphere.pool.PoolableProvider}
     *
     * @param poolableProvider the implementation of {@link org.atmosphere.pool.PoolableProvider}
     * @return this
     */
    public PoolableBroadcasterFactory poolableProvider(PoolableProvider<? extends Broadcaster, ?> poolableProvider) {
        this.poolableProvider = poolableProvider;
        this.poolableProvider.configure(config);
        return this;
    }
}
