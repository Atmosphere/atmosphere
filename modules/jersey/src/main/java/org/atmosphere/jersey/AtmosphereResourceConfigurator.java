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
package org.atmosphere.jersey;

import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.api.core.ResourceConfigurator;

import java.util.Collections;


/**
 * Automatically add the {@link AtmosphereFilter} to the list of {@link ResourceConfig}.
 *
 * @author Jeanfrancois Arcand
 * @author Paul Sandoz
 */
public class AtmosphereResourceConfigurator implements ResourceConfigurator {

    @Override
    public void configure(ResourceConfig config) {
        Collections.addAll(config.getClasses(),
                AtmosphereProviders.BroadcasterProvider.class,
                BroadcasterFactoryInjector.PerRequest.class,
                BroadcasterFactoryInjector.Singleton.class,
                BroadcasterInjector.PerRequest.class,
                BroadcasterInjector.Singleton.class,
                AtmosphereResourceInjector.PerRequest.class,
                AtmosphereResourceInjector.Singleton.class);
        config.getResourceFilterFactories().add(AtmosphereFilter.class);
    }
}
