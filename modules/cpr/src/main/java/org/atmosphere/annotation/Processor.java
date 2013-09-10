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
package org.atmosphere.annotation;

import org.atmosphere.cpr.AtmosphereFramework;

/**
 * Class annotated with {@link org.atmosphere.config.AtmosphereAnnotation} must implement this interface in order to get invoked
 * when the {@link org.atmosphere.cpr.AtmosphereFramework#init()} execute. Classes implementing this interface will
 * have a chance to process annotated classes and take the appropriate action.
 */
public interface Processor {

    /**
     * Invoked by the {@link org.atmosphere.cpr.AnnotationHandler} when an annotation is detected.
     *
     * @param framework       the {@link org.atmosphere.cpr.AtmosphereFramework}
     * @param annotatedClass the annotated classes.
     */
    public void handle(final AtmosphereFramework framework, final Class<?> annotatedClass);

}
