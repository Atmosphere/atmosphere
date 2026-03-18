/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.agui.processor;

import org.atmosphere.agui.annotation.AgUiAction;
import org.atmosphere.agui.annotation.AgUiEndpoint;
import org.atmosphere.agui.runtime.AgUiHandler;
import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Annotation processor for {@link AgUiEndpoint}. Discovered by Atmosphere's
 * annotation scanning infrastructure via {@link AtmosphereAnnotation}. Scans the
 * annotated class for an {@link AgUiAction} method and registers an
 * {@link AgUiHandler} at the configured path.
 */
@AtmosphereAnnotation(AgUiEndpoint.class)
public class AgUiEndpointProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(AgUiEndpointProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            var annotation = annotatedClass.getAnnotation(AgUiEndpoint.class);
            if (annotation == null) {
                return;
            }

            var instance = framework.newClassInstance(Object.class, annotatedClass);

            // Find the @AgUiAction method
            Method actionMethod = null;
            for (var method : annotatedClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(AgUiAction.class)) {
                    actionMethod = method;
                    break;
                }
            }

            if (actionMethod == null) {
                logger.error("No @AgUiAction method found in {}", annotatedClass.getName());
                return;
            }

            var handler = new AgUiHandler(instance, actionMethod);
            framework.addAtmosphereHandler(annotation.path(), handler, new ArrayList<>());

            logger.info("AG-UI endpoint registered at {}", annotation.path());
        } catch (Exception e) {
            logger.error("Failed to register AG-UI endpoint from {}", annotatedClass.getName(), e);
        }
    }
}
