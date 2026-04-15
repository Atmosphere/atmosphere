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
package org.atmosphere.cpr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandlerRegistryNormalizePathTest {

    @Test
    void normalizePathWithoutWildcard() {
        var config = new AtmosphereConfig(new AtmosphereFramework());
        var registry = new HandlerRegistry(config, new InterceptorRegistry(config));
        assertEquals("/chat", registry.normalizePath("/chat"));
    }

    @Test
    void normalizePathWithTrailingSlash() {
        var config = new AtmosphereConfig(new AtmosphereFramework());
        var registry = new HandlerRegistry(config, new InterceptorRegistry(config));
        var result = registry.normalizePath("/chat/");
        // Should append the mapping regex after trailing slash
        assertEquals("/chat/" + AtmosphereFramework.MAPPING_REGEX, result);
    }

    @Test
    void normalizePathWithWildcard() {
        var config = new AtmosphereConfig(new AtmosphereFramework());
        var registry = new HandlerRegistry(config, new InterceptorRegistry(config));
        var result = registry.normalizePath("/chat/*");
        // Wildcard gets replaced with the mapping regex
        assertEquals("/chat/" + AtmosphereFramework.MAPPING_REGEX, result);
    }

    @Test
    void normalizePathSimplePath() {
        var config = new AtmosphereConfig(new AtmosphereFramework());
        var registry = new HandlerRegistry(config, new InterceptorRegistry(config));
        assertEquals("/api/v1", registry.normalizePath("/api/v1"));
    }

    @Test
    void normalizePathCustomRegex() {
        var config = new AtmosphereConfig(new AtmosphereFramework());
        var registry = new HandlerRegistry(config, new InterceptorRegistry(config));
        registry.mappingRegex("[a-z]+");
        var result = registry.normalizePath("/chat/*");
        assertEquals("/chat/[a-z]+", result);
    }

    @Test
    void mappingRegexGetterSetter() {
        var config = new AtmosphereConfig(new AtmosphereFramework());
        var registry = new HandlerRegistry(config, new InterceptorRegistry(config));
        assertEquals(AtmosphereFramework.MAPPING_REGEX, registry.mappingRegex());
        registry.mappingRegex("[0-9]+");
        assertEquals("[0-9]+", registry.mappingRegex());
    }
}
