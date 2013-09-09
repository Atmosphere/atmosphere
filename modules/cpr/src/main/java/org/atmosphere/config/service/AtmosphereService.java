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
package org.atmosphere.config.service;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.interceptor.HeartbeatInterceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation which exactly acts as a {@link org.atmosphere.config.service.ManagedService} annotated resource, but that can
 * be used with framework like Jersey, Wicket or any framework running the Atmosphere Framework. The annotation allow configuring
 * Atmosphere's components like {@link Broadcaster}, {@link AtmosphereInterceptor}, etc.
 *
 * This annotation doesn't install any Atmosphere Component like {@link ManagedService}, {@link org.atmosphere.cpr.AtmosphereHandler}
 * or {@link org.atmosphere.websocket.WebSocketHandler}. The framework supporting the annotation must deploy itself an Atmosphere's Service or
 * When specified, The {@link #servlet()} returned value will be used to install a
 * {@link org.atmosphere.handler.ReflectorServletProcessor} that will dispatch requests to the {@link javax.servlet.Servlet}. You can
 * customize the request dispatch bu setting the {@link #dispatch()} to false. When set to false, the {@link org.atmosphere.handler.ReflectorServletProcessor#onRequest(org.atmosphere.cpr.AtmosphereResource)}
 * will never be invoked.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AtmosphereService {
    /**
     * Add {@link org.atmosphere.cpr.AtmosphereResourceEventListener} to track internal events.
     */
    public Class<? extends AtmosphereResourceEventListener>[] listeners() default {};

    /**
     * A list of {@link BroadcastFilter}
     */
    Class<? extends BroadcastFilter>[] broadcastFilters() default {};

    /**
     * The {@link org.atmosphere.cpr.Broadcaster} class name
     *
     * @return The {@link org.atmosphere.cpr.Broadcaster} class name
     */
    Class<? extends Broadcaster> broadcaster() default DefaultBroadcaster.class;

    /**
     * A list of {@link org.atmosphere.cpr.AtmosphereInterceptor} to install. Default are
     * , {@link org.atmosphere.client.TrackMessageSizeInterceptor} and {@link org.atmosphere.interceptor.HeartbeatInterceptor}
     */
    Class<? extends AtmosphereInterceptor>[] interceptors() default {
            TrackMessageSizeInterceptor.class,
            HeartbeatInterceptor.class
    };

    /**
      * Atmosphere's config that will be passed to the associated {@link org.atmosphere.cpr.AtmosphereHandler}. Atmosphere's config are defined
      * delimited using "=" and separated using coma.
      * @return Atmosphere's config that will be passed to the associated {@link org.atmosphere.cpr.AtmosphereHandler}. Atmosphere's config are defined
      * delimited using "=" and separated using coma.
      */
     String[] atmosphereConfig() default {};

    /**
     * The {@link org.atmosphere.cpr.BroadcasterCache} class name
     *
     * @return The {@link org.atmosphere.cpr.Broadcaster} class name. Default is {@link org.atmosphere.cache.UUIDBroadcasterCache}
     */
    Class<? extends BroadcasterCache> broadcasterCache() default UUIDBroadcasterCache.class;

    /**
     * The Servlet instance to instantiate at startup.
     */
    String servlet() default "";

    /**
     * The Servlet path  instance to instantiate at startup.
     */
    String path() default "/";

    /**
     * Dispatch the managed {@link org.atmosphere.cpr.AtmosphereResource} to the mapped
     * {@link org.atmosphere.cpr.AtmosphereHandler#onRequest(org.atmosphere.cpr.AtmosphereResource)}. If set to false, all
     * HTTP Get operation will not invoke the {@link org.atmosphere.handler.ReflectorServletProcessor#onRequest(org.atmosphere.cpr.AtmosphereResource)}.
     * The HTTP method can be customized using {@link org.atmosphere.cpr.ApplicationConfig#ATMOSPHERERESOURCE_INTERCEPTOR_METHOD} value via
     * {@link #atmosphereConfig}.  If the {@link #servlet()} is undefined, changing that value has no effect.
     */
    boolean dispatch() default true;
}
