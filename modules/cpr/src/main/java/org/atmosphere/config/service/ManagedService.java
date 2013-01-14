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
package org.atmosphere.config.service;

import org.atmosphere.cpr.AtmosphereResourceEventListener;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Meta annotation that configure Atmosphere with
 * <ul>
 *     <li>The {@link org.atmosphere.cache.HeaderBroadcasterCache}for caching message. </li>
 *     <li>The {@link org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor} for managing the connection lifecycle</li>
 *     <li>The {@link org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor} for pushing messages to suspended connection</li>
 *     <li>The {@link org.atmosphere.client.TrackMessageSizeInterceptor} for making sure messages are delivered entirely</li>
 *     <li>The {@link org.atmosphere.interceptor.HeartbeatInterceptor} for keeping the connection active</li>
 * </ul>
 *
 * Annotating your {@link org.atmosphere.cpr.AtmosphereHandler} is the same as doing:
 * <blockquote>
 @AtmosphereHandlerService(
        path = "/chat",
        broadcasterCache = HeaderBroadcasterCache.class,
        interceptors = {AtmosphereResourceLifecycleInterceptor.class,
        BroadcastOnPostAtmosphereInterceptor.class,
        TrackMessageSizeInterceptor.class,
        HeartbeatInterceptor.class})
 * </blockquote>
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ManagedService {
    /**
     * The mapping path, or context-root used to map this AtmosphereHandler
     * @return mapping path, or context-root used to map this AtmosphereHandler
     */
    String path() default "/";
    /**
     * Add {@link AtmosphereResourceEventListener} to track internal events.
     */
    public Class<? extends AtmosphereResourceEventListener>[] listeners() default {};
}
