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

/**
 * Subscribe to the "value", or topic. This annotation will create a {@link org.atmosphere.cpr.Broadcaster} with the
 * value as ID, and suspend the underlying connection. This annotation does the same as {@link Suspend}, but create
 * the Broadcaster automatically from the value.
 * <p/>
 * That annotation doesn't allow configuring the {@link org.atmosphere.annotation.Suspend#outputComments()} value. The
 * default value is set to false. If you want to support http-streaming, make sure your client set the {@link org.atmosphere.cpr.HeaderConfig#X_ATMOSPHERE_TRANSPORT}
 * header to "streaming".
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Subscribe {

    /**
     * The value that will be used to create or lookup a {@link org.atmosphere.cpr.Broadcaster}
     *
     * @return The value that will be used to create or lookup a {@link org.atmosphere.cpr.Broadcaster}
     */
    String value();

    /**
     * Add {@link org.atmosphere.cpr.AtmosphereResourceEventListener} to the broadcast operation.
     */
    Class<? extends AtmosphereResourceEventListener>[] listeners() default {};

    /**
     * Write the returned entity back to the calling connection. Default is false.
     * @return true if the entity needs to be written back to the calling connection.
     */
    boolean writeEntity() default true;

    /**
     * The timeout in millseconds before the connection is resumed. Default is 30 seconds
     * @return The timeout before the connection is resumed. Default is 30 seconds
     */
    int timeout() default 30000;
}
