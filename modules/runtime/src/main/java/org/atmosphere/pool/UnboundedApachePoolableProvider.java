/*
 * Copyright 2017 Async-IO.org
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

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.Broadcaster;
import org.atmosphere.runtime.DefaultBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An Unbounded Broadcaster Pool Provider of {@link Broadcaster}
 *
 * @author Jean-Francois Arcand
 */
public class UnboundedApachePoolableProvider implements PoolableProvider<Broadcaster, GenericObjectPool> {
    private final Logger logger = LoggerFactory.getLogger(UnboundedApachePoolableProvider.class);

    protected GenericObjectPool<Broadcaster> genericObjectPool;
    protected AtmosphereConfig config;
    protected final GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
    protected final AbandonedConfig abandonedConfig = new AbandonedConfig();
    private final AtomicLong count = new AtomicLong();

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        configureGenericObjectPoolConfig();
        genericObjectPool = new GenericObjectPool<Broadcaster>(new BroadcasterFactory(), poolConfig, abandonedConfig);
    }

    protected void configureGenericObjectPoolConfig(){
        poolConfig.setMaxTotal(Integer.MAX_VALUE);
    }

    @Override
    public Broadcaster borrowBroadcaster(Object id) {
        try {
            return DefaultBroadcaster.class.cast(genericObjectPool.borrowObject()).rename(id.toString());
        } catch (Exception e) {
            logger.error("", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public PoolableProvider returnBroadcaster(Broadcaster b) {
        logger.trace("Return {} now at size {}", b.getID(), genericObjectPool.getNumActive());
        try {
            genericObjectPool.returnObject(b);
        } catch (IllegalStateException ex) {
            logger.trace("", ex);
        }
        return this;
    }

    @Override
    public long poolSize() {
        return genericObjectPool.getCreatedCount();
    }

    @Override
    public long activeBroadcaster() {
        return genericObjectPool.getNumActive();
    }

    @Override
    public GenericObjectPool implementation() {
        return genericObjectPool;
    }

    private final class BroadcasterFactory extends BasePooledObjectFactory<Broadcaster> {

        @Override
        public Broadcaster create() {
            logger.trace("Creating Broadcaster {}", count.getAndIncrement());
            return PoolableBroadcasterFactory.class.cast(config.getBroadcasterFactory()).createBroadcaster();
        }

        @Override
        public PooledObject<Broadcaster> wrap(Broadcaster broadcaster) {
            logger.trace("Wapping Object {}", broadcaster.getID());
            return new DefaultPooledObject<Broadcaster>(broadcaster);
        }

    }

}
