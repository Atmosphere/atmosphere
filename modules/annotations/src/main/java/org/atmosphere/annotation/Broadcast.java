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

import org.atmosphere.cpr.BroadcastFilter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Broadcast to all suspended response the value of the method annotated with this annotation.
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Broadcast {

    String value() default "/*";

    /**
     * Add {@link BroadcastFilter}s to the broadcast operation.
     */
    Class<? extends BroadcastFilter>[] filters() default {};

    /**
     * Resume all suspended response on the first broadcast operation.
     */
    boolean resumeOnBroadcast() default false;

    /**
     * Should the broadcast be delayed? A value of 0 means
     * the broadcast be delayed until a normal broadcast operation
     * happens. Any other value will be evaluated as seconds
     */
    int delay() default -1;

    /**
     * Write the returned entity back to the calling connection. Default is false.
     * @return true if the entity needs to be written back to the calling connection.
     */
    boolean writeEntity() default true;
}
