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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.llm.CleartextApiKeyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@code llm.*} &rarr; {@code spring.ai.openai.*} mapping documented on
 * {@link AtmosphereSpringAiAlibabaEnvironmentPostProcessor}, which runs it on
 * Spring Boot 4, and {@link AtmosphereSpringAiAlibabaBoot3EnvironmentListener},
 * which runs it on Spring Boot 3. It names no Spring Boot post-processor type,
 * so either line loads it.
 */
final class SpringAiAlibabaLlmBridge {

    static final String SOURCE_NAME = "atmosphere-spring-ai-alibaba-llm-bridge";

    /**
     * The local Ollama endpoint ({@link AiConfig#OLLAMA_ENDPOINT}) as Spring AI
     * 1.x, the line the Alibaba agent framework pins, takes it: its
     * {@code OpenAiApi} appends {@code /v1/chat/completions} to the base URL,
     * so the {@code /v1} the OpenAI SDK expects would be doubled.
     */
    static final String LOCAL_BASE_URL = springAi1BaseUrl(AiConfig.OLLAMA_ENDPOINT);

    /** The key a local backend is sent: it ignores it, Spring AI requires one. */
    static final String LOCAL_PLACEHOLDER_API_KEY = "not-needed-for-local";

    private static final Logger logger =
            LoggerFactory.getLogger(SpringAiAlibabaLlmBridge.class);

    private SpringAiAlibabaLlmBridge() {
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
        // Skip if the sibling atmosphere-spring-ai bridge already ran — it
        // adds the same MapPropertySource under a different name. Two
        // identical bridges are harmless (idempotent), but the log line
        // would lie about which adapter wired the values.
        if (env.getPropertySources().contains("atmosphere-spring-ai-llm-bridge")) {
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
            // auto-configuration refuses to build a ChatModel without one.
            apiKey = LOCAL_PLACEHOLDER_API_KEY;
        }
        if (apiKey != null && isBlank(env.getProperty("spring.ai.openai.api-key"))) {
            mapped.put("spring.ai.openai.api-key", apiKey);
        }

        var baseUrl = firstNonBlank(
                env.getProperty("llm.base-url"),
                env.getProperty("LLM_BASE_URL"));
        if (baseUrl == null && local) {
            // llm.mode=local means the local Ollama endpoint, as for the
            // built-in runtime; without this the ChatModel called
            // api.openai.com with the user's prompt.
            baseUrl = LOCAL_BASE_URL;
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
            // LLM_BASE_URL is documented with /v1 for every runtime
            // (http://localhost:11434/v1); Spring AI 1.x would call
            // .../v1/v1/chat/completions, a 404. An explicit
            // spring.ai.openai.base-url is the user's and is never rewritten.
            mapped.put("spring.ai.openai.base-url", springAi1BaseUrl(baseUrl));
            if (isApiRootWithoutV1(baseUrl)) {
                // An API root with a version of its own (Gemini's
                // .../v1beta/openai): Spring AI 1.x's default paths would add
                // /v1 to it, so the paths drop it.
                if (isBlank(env.getProperty("spring.ai.openai.chat.completions-path"))) {
                    mapped.put("spring.ai.openai.chat.completions-path", "/chat/completions");
                }
                if (isBlank(env.getProperty("spring.ai.openai.embedding.embeddings-path"))) {
                    mapped.put("spring.ai.openai.embedding.embeddings-path", "/embeddings");
                }
            }
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
            // opts in. Thrown here, the application fails to start. In Spring
            // AI 1.x spring.ai.openai.chat.base-url overrides the shared one.
            var effectiveKey = firstNonBlank(env.getProperty("spring.ai.openai.chat.api-key"),
                    env.getProperty("spring.ai.openai.api-key"),
                    (String) mapped.get("spring.ai.openai.api-key"));
            var effectiveUrl = firstNonBlank(env.getProperty("spring.ai.openai.chat.base-url"),
                    env.getProperty("spring.ai.openai.base-url"),
                    (String) mapped.get("spring.ai.openai.base-url"));
            CleartextApiKeyPolicy.requireAllowed(logger, "Spring AI Alibaba", effectiveUrl, effectiveKey,
                    CleartextApiKeyPolicy.optedIn(env.getProperty(CleartextApiKeyPolicy.ALLOW_PROPERTY)));
        }

        if (!mapped.isEmpty()) {
            env.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, mapped));
            logger.info("Bridged llm.* → spring.ai.openai.* defaults ({} keys) for spring-ai-alibaba",
                    mapped.size());
        }
    }

    /**
     * {@code baseUrl} as Spring AI 1.x takes it: trimmed, without one trailing
     * slash and one trailing {@code /v1}, since its {@code OpenAiApi} appends
     * {@code /v1/chat/completions} to the base URL.
     *
     * @param baseUrl an OpenAI-compatible base URL, with or without {@code /v1}
     * @return the base URL without the trailing {@code /v1}
     */
    static String springAi1BaseUrl(String baseUrl) {
        var url = baseUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - "/v1".length());
        }
        return url;
    }

    /**
     * Whether {@code baseUrl} is an API root that carries its own path but not
     * a trailing {@code /v1}, such as Gemini's
     * {@code https://generativelanguage.googleapis.com/v1beta/openai}: Spring AI
     * 1.x's default {@code /v1/...} paths do not apply to it. A bare host
     * ({@code http://127.0.0.1:8000}) is not one: Spring AI adds {@code /v1}.
     *
     * @param baseUrl an OpenAI-compatible base URL
     * @return {@code true} when the default {@code /v1} paths must not be appended
     */
    static boolean isApiRootWithoutV1(String baseUrl) {
        var url = baseUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            return false;
        }
        try {
            var path = URI.create(url).getPath();
            return path != null && !path.isEmpty() && !path.equals("/");
        } catch (IllegalArgumentException e) {
            logger.debug("Base URL '{}' is not a URI; Spring AI's default paths kept", baseUrl, e);
            return false;
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
