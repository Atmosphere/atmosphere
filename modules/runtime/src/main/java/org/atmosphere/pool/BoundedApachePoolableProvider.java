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

import org.atmosphere.runtime.ApplicationConfig;
import org.atmosphere.runtime.Broadcaster;
import org.atmosphere.runtime.DefaultBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.atmosphere.runtime.ApplicationConfig.BROADCASTER_FACTORY_POOL_SIZE;

/**
 * An Bounder Broadcaster Pool Provider of {@link org.atmosphere.runtime.Broadcaster}. The default size is 200.
 *
 * @author Jean-Francois Arcand
 */
public class BoundedApachePoolableProvider extends UnboundedApachePoolableProvider {
    private final Logger logger = LoggerFactory.getLogger(BoundedApachePoolableProvider.class);
    private long waitFor = 10000;

    @Override
    protected void configureGenericObjectPoolConfig() {
        poolConfig.setMaxTotal(config.getInitParameter(BROADCASTER_FACTORY_POOL_SIZE, 200));
        waitFor = TimeUnit.SECONDS.toMillis(config.getInitParameter(ApplicationConfig.BROADCASTER_FACTORY_EMPTY_WAIT_TIME_IN_SECONDS, 10000));
    }

    @Override
    public Broadcaster borrowBroadcaster(Object id) {
        try {
            return DefaultBroadcaster.class.cast(genericObjectPool.borrowObject(waitFor)).rename(id.toString());
        } catch (Exception e) {
            logger.error("", e);
            throw new RuntimeException(e);
        }
    }
}
