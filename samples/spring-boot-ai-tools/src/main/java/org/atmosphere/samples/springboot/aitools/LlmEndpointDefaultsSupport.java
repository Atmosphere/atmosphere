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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AiConfig;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The endpoint defaults documented on {@code LlmEndpointDefaults}, which applies
 * them on Spring Boot 4, and {@link LlmEndpointDefaultsBoot3Listener}, which
 * applies them on Spring Boot 3 ({@code -Pspring-boot3}). It names no Spring
 * Boot post-processor type, so either line loads it.
 */
final class LlmEndpointDefaultsSupport {

    static final String SOURCE_NAME = "ai-tools-llm-endpoint-defaults";

    /**
     * After {@code AtmosphereAiLlmPropertyBridge} ({@code LOWEST_PRECEDENCE - 10}),
     * before a post-processor with no order ({@code LOWEST_PRECEDENCE}). A literal:
     * the bridge's constant is package-private in {@code org.atmosphere.spring.boot}.
     */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 5;

    private LlmEndpointDefaultsSupport() {
    }

    /**
     * Fills the blank {@code llm.mode}, {@code llm.model} and {@code llm.base-url}.
     * A second call on the same environment changes nothing: the property
     * source is added once, and a value already set is never overwritten.
     *
     * @param environment the application's environment
     */
    static void apply(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        var mode = valueOr(environment, "llm.mode", AiConfig.DEFAULT_MODE, defaults);
        var model = valueOr(environment, "llm.model", AiConfig.DEFAULT_MODEL, defaults);
        if (isBlank(environment.getProperty("llm.base-url"))) {
            defaults.put("llm.base-url", AiConfig.resolveBaseUrl(mode, null, model));
        }
        if (!defaults.isEmpty()) {
            // First, not last: application.yml declares these as empty strings,
            // which would shadow a lower-precedence source. Only blank values
            // are put here, so nothing a user set is overridden.
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, defaults));
        }
    }

    private static String valueOr(ConfigurableEnvironment environment, String key, String fallback,
                                  Map<String, Object> defaults) {
        var value = environment.getProperty(key);
        if (isBlank(value)) {
            defaults.put(key, fallback);
            return fallback;
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
