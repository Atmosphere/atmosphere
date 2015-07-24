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

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.interceptor.SuspendTrackerInterceptor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Meta annotation that configure Atmosphere with
 * <ul>
 *     <li>The {@link org.atmosphere.cache.UUIDBroadcasterCache}for caching message. </li>
 *     <li>The {@link org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor} for managing the connection lifecycle</li>
 *     <li>The {@link org.atmosphere.client.TrackMessageSizeInterceptor} for making sure messages are delivered entirely</li>
 * </ul>
 *
 * Annotating your {@link org.atmosphere.cpr.AtmosphereHandler} is the same as doing:
 * <pre><blockquote>
 @AtmosphereHandlerService(
        path = "/chat",
        broadcasterCache = UUIDBroadcasterCache.class,
        interceptors = {
            AtmosphereResourceLifecycleInterceptor.class,
            TrackMessageSizeInterceptor.class,
            IdleResourceInterceptor.class
            SuspendTrackerInterceptor.class})
 * </blockquote></pre>
 *
 * This annotation can be used with @Get, @Post, @Delete, @Ready, @Singleton and @Resume
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ManagedService {
    /**
     * The mapping path or context-root used to map this AtmosphereHandler
     *
     * @return mapping path or context-root used to map this AtmosphereHandler
     */
    String path() default "/";

    /**
     * Add {@link AtmosphereResourceEventListener} to track internal events.
     */
    public Class<? extends AtmosphereResourceEventListener>[] listeners() default {};

    /**
     * The {@link org.atmosphere.cpr.Broadcaster} class name
     *
     * @return The {@link org.atmosphere.cpr.Broadcaster} class name
     */
    Class<? extends Broadcaster> broadcaster() default DefaultBroadcaster.class;

    /**
     * A list of {@link org.atmosphere.cpr.AtmosphereInterceptor} to install. Defined interceptor will be appended to the default set: {@link AtmosphereResourceLifecycleInterceptor},
     * {@link org.atmosphere.config.managed.ManagedServiceInterceptor}, {@link TrackMessageSizeInterceptor},
     * {@link HeartbeatInterceptor} and {@link SuspendTrackerInterceptor}
     */
    Class<? extends AtmosphereInterceptor>[] interceptors() default {};

    /**
     * Atmosphere's configuration that will be passed to the associated {@link org.atmosphere.cpr.AtmosphereHandler}. Configuration
     * name and value is delimited by "=", and different configuration lines are separated by comma.
     */
    String[] atmosphereConfig() default {};

    /**
     * The {@link org.atmosphere.cpr.BroadcasterCache} class name
     * <p/>
     * Default is {@link UUIDBroadcasterCache}.
     *
     * @return The {@link org.atmosphere.cpr.Broadcaster} class name.
     */
    Class<? extends BroadcasterCache> broadcasterCache() default UUIDBroadcasterCache.class;

    /**
     * A list of {@link org.atmosphere.cpr.BroadcastFilter}
     */
    Class<? extends BroadcastFilter>[] broadcastFilters() default {};
}
