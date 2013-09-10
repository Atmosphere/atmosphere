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
package org.atmosphere.interceptor;

/**
 * A simple marker class to use with {@link org.atmosphere.cpr.AtmosphereInterceptor} in order to determine in which
 * position in the interceptor's chain the AtmosphereInterceptor must be installed.
 *
 * Normally all {@link org.atmosphere.cpr.AtmosphereInterceptor} are installed using the {@link InvokationOrder.PRIORITY#AFTER_DEFAULT}
 *
 * @author Jeanfrancois Arcand
 */
public interface InvokationOrder {

    enum PRIORITY {
        /**
         * The AtmosphereInterceptor must be executed before the default set of AtmosphereInterceptor
         */
        AFTER_DEFAULT,
        /**
         * The AtmosphereInterceptor must be executed after the default set of AtmosphereInterceptor
         */
        BEFORE_DEFAULT,
        /**
         * The AtmosphereInterceptor must be executed at first, before any AtmosphereInterceptor.
         */
        FIRST_BEFORE_DEFAULT
    }

    static PRIORITY AFTER_DEFAULT = PRIORITY.AFTER_DEFAULT;
    static PRIORITY BEFORE_DEFAULT = PRIORITY.BEFORE_DEFAULT;
    static PRIORITY FIRST_BEFORE_DEFAULT = PRIORITY.FIRST_BEFORE_DEFAULT;

    /**
     * Return the priority an AtmosphereInterceptor must be executed.
     * @return PRIORITY
     */
    PRIORITY priority();

}
