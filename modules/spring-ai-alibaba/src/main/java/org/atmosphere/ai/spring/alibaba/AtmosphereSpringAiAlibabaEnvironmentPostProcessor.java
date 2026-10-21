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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
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
 *   <li>{@code llm.base-url} → {@code spring.ai.openai.base-url}, without the
 *       trailing {@code /v1} the other runtimes are given (Spring AI 1.x
 *       appends it); with none, {@code llm.mode=local} ({@code LLM_MODE=local})
 *       maps the local Ollama endpoint and, when no key is set, a placeholder
 *       key</li>
 *   <li>with neither, a model that is not OpenAI's ({@code gemini-*}, ...) maps
 *       the endpoint the built-in runtime derives from it
 *       ({@code AiConfig.resolveBaseUrl}), Gemini's OpenAI-compatible one, with
 *       the chat and embedding paths that endpoint takes</li>
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
        var local = "local".equalsIgnoreCase(firstNonBlank(
                env.getProperty("llm.mode"),
                env.getProperty("LLM_MODE")));

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
