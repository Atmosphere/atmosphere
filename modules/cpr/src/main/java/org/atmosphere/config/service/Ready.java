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

import org.atmosphere.config.managed.Encoder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Use this annotation with the {@link ManagedService} annotation. A method annotated with this annotation will be
 * invoked when the connection has been suspended and ready.
 *
 * @author Jeanfrancois Arcand
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Ready {

    static enum DELIVER_TO { RESOURCE, BROADCASTER, ALL}

    /**
     * Broadcast the returned value only to the calling resource {@link org.atmosphere.config.service.Ready.DELIVER_TO#RESOURCE},
     * to its associated Broadcaster {@link org.atmosphere.config.service.Ready.DELIVER_TO#BROADCASTER}
     * or to all created Broadcasters {@link org.atmosphere.config.service.Ready.DELIVER_TO#ALL}
     *
     * @return the {@link org.atmosphere.config.service.Ready.DELIVER_TO}
     */
    DELIVER_TO value() default  DELIVER_TO.RESOURCE;

    /**
     * A list of {@link org.atmosphere.config.managed.Encoder}
     */
    Class<? extends Encoder>[] encoders() default {};
}
