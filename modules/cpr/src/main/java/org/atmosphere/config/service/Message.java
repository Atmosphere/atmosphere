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

import org.atmosphere.config.managed.Decoder;
import org.atmosphere.config.managed.Encoder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotate a method that will get invoked when String messages are broadcasted using the {@link org.atmosphere.cpr.Broadcaster}
 * associated with the class where the annotation is associated.
 *
 * @author Jeanfrancois Arcand
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Message {

    /**
     * A list of {@link org.atmosphere.config.managed.Encoder}
     */
    Class<? extends Encoder>[] encoders() default {};

    /**
     * A list of {@link org.atmosphere.config.managed.Decoder}
     */
    Class<? extends Decoder>[] decoders() default {};
}
