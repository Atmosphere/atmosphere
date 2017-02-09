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
package org.atmosphere.handler;

import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereHandler;

/**
 * Marker class for an {@link org.atmosphere.runtime.AtmosphereHandler} proxy of a POJO object.
 *
 * @author Jeanfrancois Arcand
 */
public interface AnnotatedProxy extends AtmosphereHandler {

    /**
     * The Object the {@link org.atmosphere.runtime.AtmosphereHandler} is proxying.
     *
     * @return
     */
    Object target();

    /**
     * Return true if {@link org.atmosphere.config.service.PathParam} are supported.
     *
     * @return true if {@link org.atmosphere.config.service.PathParam} are supported.
     */
    boolean pathParams();

    /**
     * Configure the proxy.
     * @param config
     * @param c
     * @return
     */
    AnnotatedProxy configure(AtmosphereConfig config, Object c);

}
