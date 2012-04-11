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
package org.atmosphere.plugin.jaxrs;

import com.sun.jersey.api.core.ResourceConfig;
import org.atmosphere.jersey.AtmosphereResourceConfigurator;

import java.util.Collections;

/**
 * @author Jeanfrancois Arcand
 */
public class AtmosphereJaxrs2Configurator extends AtmosphereResourceConfigurator {

    @Override
    public void configure(ResourceConfig config) {
        super.configure(config);
                Collections.addAll(config.getClasses(),
                        ExecutionContextInjector.PerRequest.class,
                        ExecutionContextInjector.Singleton.class);
        config.getResourceFilterFactories().add(Jaxrs2Filter.class);
    }
}
