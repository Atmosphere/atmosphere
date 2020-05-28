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
package org.atmosphere.annotation;

import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.HeaderConfig;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Suspend the response and use the Broadcaster associated with {@link org.atmosphere.cpr.HeaderConfig#X_ATMOSPHERE_TRACKING_ID}
 * to publish the result.
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Asynchronous {

    /**
     * Wait for {@link org.atmosphere.cpr.AtmosphereResource} before executing the {@link org.atmosphere.cpr.Broadcaster#awaitAndBroadcast(Object, long, java.util.concurrent.TimeUnit)}.
     * @return true if the broadcast operation must wait before executing.
     */
    boolean waitForResource() default true;

    /**
     * Add {@link org.atmosphere.cpr.BroadcastFilter} to the broadcast operation.
     */
    Class<? extends BroadcastFilter>[] broadcastFilter() default {};

    /**
     * The header value used to create a {@link org.atmosphere.cpr.Broadcaster} that will be used to broadcast the
     * asynchronous execution.
     * @return The header value used to create a {@link org.atmosphere.cpr.Broadcaster}
     */
    String header() default HeaderConfig.X_ATMOSPHERE_TRACKING_ID;

    /**
     * The time a connection will be suspended if no broadcast happens. Same as {@link org.atmosphere.annotation.Suspend#period()}
     * @return The time a connection will be suspended if no broadcast happens.
     */
    int period() default 5 * 60 * 1000;

    /**
     * Add {@link AtmosphereResourceEventListener} to the broadcast operation.
     */
    Class<? extends AtmosphereResourceEventListener>[] eventListeners() default {};

    /**
     * Write the returned entity back to the calling connection. Default is false.
     * @return true if the entity needs to be written back to the calling connection.
     */
    boolean writeEntity() default true;

    /**
     * If the @Produces annotation is missing, this value will be used instead.
     * @return the default content-type used if the @Produces annotation is missing.
     */
    String contentType() default "";
}
