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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Schedule periodic broadcast operations to the set of suspended response.
 *
 * @author Jeanfrancois Arcand
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Schedule {

    /**
     * The period for broadcast (in second).
     *
     * @return
     */
    int period() default 60;

    /**
     * The period before starting the broadcast (in second).
     *
     * @return
     */
    int waitFor() default 0;

    /**
     * Resume all suspended response on the first broadcast operation.
     */
    boolean resumeOnBroadcast() default false;

}
