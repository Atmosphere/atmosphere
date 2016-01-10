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

import java.lang.annotation.*;

/**
 * Use this annotation with the {@link org.atmosphere.config.service.ManagedService} annotation. Annotate a field which will get appropriate value when
 * the service is instantiated for given path. The syntax of the path is the following
 * /whatever/{varX}/.../whatever/.../{varY}/...
 * The @PathVariable annotation may be given a name, otherwise verbatim name of field is used
 * The number of slashes in the matched and matching paths shall be equal, otherwise the result is undefined.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Documented
public @interface PathParam {
    /**
     * The URI template variable to bind to.
     */
    String value() default "";
}
