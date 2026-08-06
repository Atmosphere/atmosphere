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

import org.atmosphere.nativeimage.NativeImageMetadata;
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
            MemberCategory.ACCESS_DECLARED_FIELDS
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        ReflectionHints reflection = hints.reflection();

        // Spring-specific: the object factory this starter installs.
        registerType(reflection, SpringAtmosphereObjectFactory.class);

        // Everything else comes from the modules themselves. This used to be a
        // transcription of AtmosphereReflectiveTypes, duplicated here, in the
        // Spring Boot 3 starter and in the Quarkus build step — three copies of
        // one list, and no way for atmosphere-ai, -mcp or -agent to contribute
        // the types they load by name. Asking the SPI means a module that adds
        // a reflective lookup declares it once, beside the code doing it, and
        // every runtime picks it up.
        var metadata = NativeImageMetadata.collect(
                classLoader != null ? classLoader : getClass().getClassLoader());

        for (String typeName : metadata.reflectiveTypes()) {
            registerTypeByName(reflection, typeName);
        }
        for (String pattern : metadata.resourcePatterns()) {
            hints.resources().registerPattern(pattern);
        }

        logger.debug("Registered {} reflective type(s) and {} resource pattern(s) from "
                        + "native metadata provider(s) {}",
                metadata.reflectiveTypes().size(), metadata.resourcePatterns().size(),
                metadata.providerNames());
    }

    private void registerType(ReflectionHints reflection, Class<?> type) {
        reflection.registerType(type, HINT_CATEGORIES);
    }

    private void registerTypeByName(ReflectionHints reflection, String typeName) {
        reflection.registerType(TypeReference.of(typeName), HINT_CATEGORIES);
    }
}
