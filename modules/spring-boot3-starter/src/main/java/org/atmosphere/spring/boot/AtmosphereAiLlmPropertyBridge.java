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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes the starter's {@code atmosphere.ai.mode}, {@code model}, {@code api-key}
 * and {@code base-url} reach the runtime adapters, which read {@code llm.*}
 * ({@code LLM_*}): LangChain4j, Spring AI, Spring AI Alibaba, AgentScope,
 * Semantic Kernel, Koog and Embabel. Without it {@code atmosphere.ai.base-url}
 * configured the built-in runtime only and was silently ignored once an adapter
 * was on the classpath.
 *
 * <p>Each {@code atmosphere.ai.X} that is set (bound as {@link AtmosphereProperties}
 * binds it, relaxed names included) becomes {@code llm.X} when no source gives
 * {@code llm.X} a non-blank value, the {@code LLM_X} environment variable
 * included. The order an adapter sees is therefore: its own property
 * ({@code spring.ai.openai.base-url}, {@code atmosphere.koog.base-url}, ...),
 * then {@code llm.X} / {@code LLM_X}, then {@code atmosphere.ai.X}, then the
 * adapter's derived default. The built-in runtime's own resolution
 * ({@code atmosphere.ai.X} before {@code LLM_X}) is unchanged.</p>
 *
 * <p>The mapped values are added as the first property source: the guard above,
 * not the position, keeps a configured {@code llm.X} in front, and the position
 * lets the value beat a blank {@code llm.base-url: ${LLM_BASE_URL:}} entry, which
 * every reader treats as unset. Nothing is written outside this
 * {@link ConfigurableEnvironment}, so two applications in one JVM keep their own
 * values (Correctness Invariant #1).</p>
 *
 * <p>Ordered after {@code ConfigDataEnvironmentPostProcessor}, so
 * {@code application.yml} is loaded, and before the adapters' own
 * {@link EnvironmentPostProcessor}s, which declare no order (lowest precedence)
 * and read {@code llm.*} when they run.</p>
 */
public class AtmosphereAiLlmPropertyBridge implements EnvironmentPostProcessor, Ordered {

    /** The name of the property source holding the mapped {@code llm.*} values. */
    static final String SOURCE_NAME = "atmosphere-ai-llm-bridge";

    /**
     * After {@code ConfigDataEnvironmentPostProcessor} ({@code HIGHEST_PRECEDENCE + 10}),
     * before a post-processor with no order ({@code LOWEST_PRECEDENCE}).
     */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    /** The settings bridged, named as under both prefixes. */
    static final List<String> SETTINGS = List.of("mode", "model", "api-key", "base-url");

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAiLlmPropertyBridge.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var binder = Binder.get(environment);
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (var setting : SETTINGS) {
            var target = "llm." + setting;
            if (!isBlank(environment.getProperty(target))) {
                continue;
            }
            var value = binder.bind("atmosphere.ai." + setting, String.class).orElse(null);
            if (!isBlank(value)) {
                mapped.put(target, value);
            }
        }
        if (!mapped.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, mapped));
            logger.info("Mapped atmosphere.ai.* onto unset llm.* for the AI runtime adapters: {}",
                    mapped.keySet());
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
