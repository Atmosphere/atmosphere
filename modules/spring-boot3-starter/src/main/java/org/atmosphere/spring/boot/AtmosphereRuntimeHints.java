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
package org.atmosphere.spring.boot;

import org.atmosphere.cpr.AtmosphereReflectiveTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * {@link RuntimeHintsRegistrar} for Atmosphere Framework classes that are
 * instantiated reflectively at runtime. Registers reflection hints for
 * core framework classes, injectable SPI implementations, annotation
 * processors, and ServiceLoader resource files.
 */
public class AtmosphereRuntimeHints implements RuntimeHintsRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereRuntimeHints.class);

    private static final MemberCategory[] HINT_CATEGORIES = {
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        ReflectionHints reflection = hints.reflection();

        // Spring-specific
        registerType(reflection, SpringAtmosphereObjectFactory.class);

        // Core Atmosphere types (source of truth: AtmosphereReflectiveTypes)
        for (String typeName : AtmosphereReflectiveTypes.coreTypes()) {
            registerTypeByName(reflection, typeName);
        }

        // Pool implementations (only if commons-pool2 is on the classpath)
        if (classLoader != null) {
            try {
                classLoader.loadClass("org.apache.commons.pool2.PooledObjectFactory");
                for (String typeName : AtmosphereReflectiveTypes.poolTypes()) {
                    registerTypeByName(reflection, typeName);
                }
            } catch (ClassNotFoundException e) {
                logger.trace("commons-pool2 not on classpath; skipping pool class registration", e);
            }
        }

        // Annotation processors (instantiated reflectively by AnnotationHandler)
        for (String processor : AtmosphereReflectiveTypes.annotationProcessors()) {
            registerTypeByName(reflection, processor);
        }

        // ServiceLoader resource files
        hints.resources().registerPattern("META-INF/services/org.atmosphere.inject.Injectable");
        hints.resources().registerPattern("META-INF/services/org.atmosphere.inject.CDIProducer");
    }

    private void registerType(ReflectionHints reflection, Class<?> type) {
        reflection.registerType(type, HINT_CATEGORIES);
    }

    private void registerTypeByName(ReflectionHints reflection, String typeName) {
        reflection.registerType(TypeReference.of(typeName), HINT_CATEGORIES);
    }
}
