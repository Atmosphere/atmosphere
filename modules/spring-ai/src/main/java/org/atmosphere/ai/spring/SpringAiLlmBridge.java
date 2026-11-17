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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.llm.CleartextApiKeyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code llm.*} &rarr; {@code spring.ai.openai.*} mapping documented on
 * {@link AtmosphereSpringAiEnvironmentPostProcessor}, which runs it on Spring
 * Boot 4, and {@link AtmosphereSpringAiBoot3EnvironmentListener}, which runs it
 * on Spring Boot 3. It names no Spring Boot post-processor type, so either line
 * loads it.
 */
final class SpringAiLlmBridge {

    static final String SOURCE_NAME = "atmosphere-spring-ai-llm-bridge";

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLlmBridge.class);

    private SpringAiLlmBridge() {
    }

    /**
     * Maps the unset {@code spring.ai.openai.*} properties from {@code llm.*}.
     * A second call on the same environment changes nothing: the property
     * source is added once, and a property already set is never overwritten.
     *
     * @param env the application's environment
     */
    static void apply(ConfigurableEnvironment env) {
        if (env.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        Map<String, Object> mapped = new HashMap<>();
        // Trimmed and case-insensitive, as AiConfig and the auto-configured clients read it.
        var mode = firstNonBlank(env.getProperty("llm.mode"), env.getProperty("LLM_MODE"));
        var local = mode != null && "local".equalsIgnoreCase(mode.trim());

        var apiKey = firstNonBlank(
                env.getProperty("llm.api-key"),
                env.getProperty("LLM_API_KEY"),
                env.getProperty("OPENAI_API_KEY"),
                env.getProperty("GEMINI_API_KEY"));
        if (apiKey == null && local) {
            // A local backend ignores the credential; Spring AI's OpenAI
            // auto-configurations refuse to build without one.
            apiKey = AtmosphereSpringAiAutoConfiguration.LOCAL_PLACEHOLDER_API_KEY;
        }
        if (apiKey != null && isBlank(env.getProperty("spring.ai.openai.api-key"))) {
            mapped.put("spring.ai.openai.api-key", apiKey);
        }

        var baseUrl = firstNonBlank(
                env.getProperty("llm.base-url"),
                env.getProperty("LLM_BASE_URL"));
        if (baseUrl == null && local) {
            // llm.mode=local means the local Ollama endpoint, as for the
            // built-in runtime; without this Spring AI's own OpenAI beans (the
            // EmbeddingModel Atmosphere binds included) call api.openai.com.
            // Spring AI 2.0 hands the URL to the OpenAI SDK verbatim, /v1 included.
            baseUrl = AiConfig.OLLAMA_ENDPOINT;
        }
        if (baseUrl == null && !local) {
            // A blank base URL follows the model, as for the built-in runtime
            // (AiConfig.resolveBaseUrl): Spring AI's own default is OpenAI, so
            // a Gemini model and key went to api.openai.com. OpenAI keeps
            // Spring AI's default; with no model named nothing is mapped.
            var namedModel = firstNonBlank(
                    env.getProperty("llm.model"),
                    env.getProperty("LLM_MODEL"),
                    env.getProperty("spring.ai.openai.chat.options.model"));
            if (namedModel != null) {
                var derived = AiConfig.resolveBaseUrl(null, null, namedModel);
                if (!AiConfig.OPENAI_ENDPOINT.equals(derived)) {
                    baseUrl = derived;
                }
            }
        }
        if (baseUrl != null && isBlank(env.getProperty("spring.ai.openai.base-url"))) {
            mapped.put("spring.ai.openai.base-url", baseUrl);
        }

        var model = firstNonBlank(
                env.getProperty("llm.model"),
                env.getProperty("LLM_MODEL"));
        if (model != null && isBlank(env.getProperty("spring.ai.openai.chat.options.model"))) {
            mapped.put("spring.ai.openai.chat.options.model", model);
        }

        if (mapped.containsKey("spring.ai.openai.api-key") || mapped.containsKey("spring.ai.openai.base-url")) {
            // Atmosphere routes the key or picks the endpoint: a real key goes
            // over plain http only to a loopback host, unless the application
            // opts in. Thrown here, the application fails to start.
            // The chat client reads spring.ai.openai.chat.* over the shared keys.
            var effectiveKey = firstNonBlank(env.getProperty("spring.ai.openai.chat.api-key"),
                    env.getProperty("spring.ai.openai.api-key"),
                    (String) mapped.get("spring.ai.openai.api-key"));
            var effectiveUrl = firstNonBlank(env.getProperty("spring.ai.openai.chat.base-url"),
                    env.getProperty("spring.ai.openai.base-url"),
                    (String) mapped.get("spring.ai.openai.base-url"));
            CleartextApiKeyPolicy.requireAllowed(logger, "Spring AI", effectiveUrl, effectiveKey,
                    CleartextApiKeyPolicy.optedIn(env.getProperty(CleartextApiKeyPolicy.ALLOW_PROPERTY)));
        }

        if (!mapped.isEmpty()) {
            env.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, mapped));
            logger.info("Bridged llm.* → spring.ai.openai.* defaults ({} keys)", mapped.size());
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
