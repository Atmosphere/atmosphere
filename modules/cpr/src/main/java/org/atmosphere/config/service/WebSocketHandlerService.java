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
package org.atmosphere.config.service;

import org.atmosphere.cache.DefaultBroadcasterCache;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.websocket.WebSocketEventListener;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation for telling Atmosphere which {@link org.atmosphere.websocket.WebSocketHandler} class to use by default.
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WebSocketHandlerService {

    /**
     * A list of {@link org.atmosphere.cpr.BroadcastFilter}
     */
    Class<? extends BroadcastFilter>[] broadcastFilters() default {};

    /**
      * The {@link org.atmosphere.cpr.BroadcasterCache} class name. By default, a no-ops {@link DefaultBroadcasterCache}
      * is installed. It is strongly recommended to install the {@link org.atmosphere.cache.UUIDBroadcasterCache} to prevent
      * messages being lost.
      *
      * @return The {@link org.atmosphere.cpr.Broadcaster} class name
      */
     Class<? extends BroadcasterCache> broadcasterCache() default DefaultBroadcasterCache.class;

    /**
     * The mapping path or context-root used to map this WebSocketHandler
     *
     * @return mapping path or context-root used to map this WebSocketHandler
     */
    String path() default "/";

    /**
     * The {@link org.atmosphere.cpr.Broadcaster} class name
     *
     * @return The {@link org.atmosphere.cpr.Broadcaster} class name
     */
    Class<? extends Broadcaster> broadcaster() default DefaultBroadcaster.class;

    /**
     * A list of {@link org.atmosphere.cpr.AtmosphereInterceptor} to install
     */
    Class<? extends AtmosphereInterceptor>[] interceptors() default {};

    /**
     * Atmosphere's configuration that will be passed to the associated {@link org.atmosphere.cpr.AtmosphereHandler}. Configuration
     * name and value is delimited by "=", and different configuration lines are separated by comma.
     */
    String[] atmosphereConfig() default {};

    /**
     * Add {@link org.atmosphere.cpr.AtmosphereResourceEventListener} to track internal events.
     */
    public Class<? extends WebSocketEventListener>[] listeners() default {};

}
