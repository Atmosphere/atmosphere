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
package org.atmosphere.ai.spring.alibaba;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges Atmosphere's {@code llm.*} property convention into the
 * {@code spring.ai.openai.*} namespace expected by Spring AI's own
 * auto-configuration. Runs as an {@link EnvironmentPostProcessor} so the
 * mapped values land in the {@link ConfigurableEnvironment} <em>before</em>
 * Spring AI's {@code @AutoConfiguration} classes are evaluated.
 *
 * <p>This is a deliberate sibling of
 * {@code AtmosphereSpringAiEnvironmentPostProcessor} in {@code atmosphere-spring-ai}.
 * The CLI's {@code --runtime spring-ai-alibaba --force} overlay strips
 * {@code atmosphere-spring-ai} (its sibling overlay's adapter), so this module
 * cannot rely on the post-processor declared there — without a copy here,
 * {@code spring-ai-starter-model-openai} pulled in by the overlay sees no
 * {@code spring.ai.openai.api-key} and the OpenAI {@code ChatModel} bean
 * never builds, leaving {@code SpringAiAlibabaAgentRuntime} with no
 * {@code ReactAgent} to dispatch through.</p>
 *
 * <p>Mappings (each only applied when the source value is set and the target
 * is not already configured by the user):</p>
 * <ul>
 *   <li>{@code llm.api-key} → {@code spring.ai.openai.api-key}</li>
 *   <li>{@code llm.base-url} → {@code spring.ai.openai.base-url}</li>
 *   <li>{@code llm.model}    → {@code spring.ai.openai.chat.options.model}</li>
 * </ul>
 *
 * <p>The property source registered here has the lowest precedence among
 * Atmosphere-supplied sources, so users who set the {@code spring.ai.openai.*}
 * properties directly always win.</p>
 */
// EnvironmentPostProcessor is deprecated for removal in Spring Boot 4.0 but
// is still the only documented hook for mutating the Environment before
// auto-configurations are evaluated. Mirrors the suppression on the
// atmosphere-spring-ai sibling.
@SuppressWarnings({"deprecation", "removal"})
public class AtmosphereSpringAiAlibabaEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "atmosphere-spring-ai-alibaba-llm-bridge";

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereSpringAiAlibabaEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        // Skip if the sibling atmosphere-spring-ai bridge already ran — it
        // adds the same MapPropertySource under a different name. Two
        // identical bridges are harmless (idempotent), but the log line
        // would lie about which adapter wired the values.
        if (env.getPropertySources().contains("atmosphere-spring-ai-llm-bridge")) {
            return;
        }

        Map<String, Object> mapped = new HashMap<>();

        var apiKey = firstNonBlank(
                env.getProperty("llm.api-key"),
                env.getProperty("LLM_API_KEY"),
                env.getProperty("OPENAI_API_KEY"),
                env.getProperty("GEMINI_API_KEY"));
        if (apiKey != null && isBlank(env.getProperty("spring.ai.openai.api-key"))) {
            mapped.put("spring.ai.openai.api-key", apiKey);
        }

        var baseUrl = firstNonBlank(
                env.getProperty("llm.base-url"),
                env.getProperty("LLM_BASE_URL"));
        if (baseUrl != null && isBlank(env.getProperty("spring.ai.openai.base-url"))) {
            mapped.put("spring.ai.openai.base-url", baseUrl);
        }

        var model = firstNonBlank(
                env.getProperty("llm.model"),
                env.getProperty("LLM_MODEL"));
        if (model != null && isBlank(env.getProperty("spring.ai.openai.chat.options.model"))) {
            mapped.put("spring.ai.openai.chat.options.model", model);
        }

        if (!mapped.isEmpty()) {
            env.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, mapped));
            logger.info("Bridged llm.* → spring.ai.openai.* defaults ({} keys) for spring-ai-alibaba",
                    mapped.size());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
