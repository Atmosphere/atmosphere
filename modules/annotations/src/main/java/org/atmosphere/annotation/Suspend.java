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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Suspend the underlying response. Once suspended, a response might be allowed
 * to consume {@link Broadcast} events, depending on the scope ([@link Suspend#SCOPE}).
 * By default, a suspended response is suspended able to consume
 * any broadcasted events executed inside the same application (SCOPE.APPLICATION).
 * The period can also be per suspended response (SCOPE.REQUEST) or available to other
 * application (SCOPE.VM).
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Suspend {

    /**
     * How long a response stay suspended in {@link #timeUnit}, default is -1
     *
     * @return
     */
    int period() default -1;

    /**
     * @return
     */
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;

    enum SCOPE {
        REQUEST, APPLICATION, VM
    }

    /**
     * The Scope of the {@link org.atmosphere.cpr.Broadcaster} that will be created once the
     * response gets suspended. One final word on Broadcaster: by default,
     * a Broadcaster will broadcast using
     * all resources/classes on which the response has been suspended.
     * This behavior is configurable and you can configure it setting the appropriate scope<ul>
     * <li>REQUEST: broadcast events only to the suspended response associated with the current request.</li>
     * <li>APPLICATION: broadcast events to all suspended responses associated with the current application.</li>
     * <li>VM: broadcast events to all suspended responses created inside the current virtual machine.</li>
     * </ul>
     *
     * @return The Scope of the {@link org.atmosphere.cpr.Broadcaster} that will be created once the
     *         response gets suspended.
     */
    SCOPE scope() default SCOPE.APPLICATION;

    /**
     * By default, output some comments when suspending the connection.
     * Deprecated. No longer required
     */
    @Deprecated
    boolean outputComments() default true;

    /**
     * Resume all suspended response on the first broadcast operation.
     */
    boolean resumeOnBroadcast() default false;

    /**
     * Add {@link AtmosphereResourceEventListener} to track internal events.
     */
    Class<? extends AtmosphereResourceEventListener>[] listeners() default {};

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
