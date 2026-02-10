/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.quarkus.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import org.atmosphere.inject.InjectableObjectFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuarkusAtmosphereObjectFactory extends InjectableObjectFactory {

    private static final Logger logger = LoggerFactory.getLogger(QuarkusAtmosphereObjectFactory.class);

    @Override
    public <T, U extends T> U newClassInstance(Class<T> classType, Class<U> defaultType)
            throws InstantiationException, IllegalAccessException {
        InstanceHandle<U> handle = Arc.container().instance(defaultType);
        if (handle.isAvailable()) {
            logger.trace("Found CDI bean for {}", defaultType.getName());
            return handle.get();
        }

        logger.trace("No CDI bean for {}, delegating to InjectableObjectFactory", defaultType.getName());
        return super.newClassInstance(classType, defaultType);
    }
}
