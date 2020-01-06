/*
 * Copyright 2008-2020 Async-IO.org
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

import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.inject.AtmosphereConfigAware;

/**
 * Implements this interface for provisioning the {@link org.atmosphere.pool.PoolableBroadcasterFactory}
 * with {@link org.atmosphere.cpr.Broadcaster}
 *
 * @author Jeanfrancois Arcand
 */
public interface PoolableProvider<T extends Broadcaster, U> extends AtmosphereConfigAware {

    /**
     * Return a {@link org.atmosphere.cpr.Broadcaster}
     * @param id the name of the Broadcaster
     * @return {@link org.atmosphere.cpr.Broadcaster}
     */
    T borrowBroadcaster(Object id);

    /**
     * Return a destroyed {@link Broadcaster} instance.
     * @param b {@link org.atmosphere.cpr.Broadcaster}
     * @return this
     */
    <T extends Broadcaster, U> PoolableProvider returnBroadcaster(T b);

    /**
     * The current Pool Size
     * @return current Pool size
     */
    long poolSize();

    /**
     * Current number of active Broadcaster borrowed from the pool
     */
    long activeBroadcaster();

    /**
     * Return the current native pool implementation. For example, the GenericObjectPool from Apache Common
     * will be returned if the {@link org.atmosphere.pool.UnboundedApachePoolableProvider} is used.
     * @return the current native pool implementation
     */
    U implementation();
}
