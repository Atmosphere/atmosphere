/*
 * Copyright 2017 Async-IO.org
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Broadcast the returned value only to the calling resource {@link DELIVER_TO#RESOURCE},
 * to its associated Broadcaster {@link DELIVER_TO#BROADCASTER}
 * or to all created Broadcasters {@link DELIVER_TO#ALL}
 *
 * @author Guillaume Drouet
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DeliverTo {

    static enum DELIVER_TO { RESOURCE, BROADCASTER, ALL}

    /**
     * @return the {@link DELIVER_TO}
     */
    DELIVER_TO value();
}
