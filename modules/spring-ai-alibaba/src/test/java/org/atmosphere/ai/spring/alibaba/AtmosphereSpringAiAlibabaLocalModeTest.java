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
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code LLM_MODE=local}, the documented keyless mode, reaches the local
 * Ollama endpoint through Spring AI Alibaba. Spring AI's OpenAI
 * auto-configuration builds the {@code ChatModel} from
 * {@code spring.ai.openai.*}, and the bridge mapped only a base URL that was
 * set, so with the mode alone the user's prompt went to api.openai.com (an
 * HTTP 401 for the placeholder key). Spring AI 1.x appends {@code /v1} itself,
 * so the local endpoint is mapped without it. An explicit
 * {@code spring.ai.openai.base-url} still wins.
 *
 * <p>The process environment is removed from every environment built here, so
 * an {@code LLM_*} variable exported by the shell running the build cannot
 * change the outcome.</p>
 */
class AtmosphereSpringAiAlibabaLocalModeTest {

    @Test
    void aKeyRoutedOverPlainHttpToANonLoopbackHostFailsTheStart() {
        var env = environment(Map.of("LLM_API_KEY", "sk-test", "LLM_BASE_URL", "http://llm.invalid:8000/v1"));

        var refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null));
        // The endpoint as Spring AI 1.x would call it: the mapped base URL, without /v1.
        org.junit.jupiter.api.Assertions.assertTrue(refused.getMessage().contains("'http://llm.invalid:8000'"),
                refused.getMessage());

        var optedIn = environment(Map.of("LLM_API_KEY", "sk-test", "LLM_BASE_URL", "http://llm.invalid:8000/v1",
                org.atmosphere.ai.llm.CleartextApiKeyPolicy.ALLOW_PROPERTY, "true"));
        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(optedIn, null);
        assertEquals("sk-test", optedIn.getProperty("spring.ai.openai.api-key"));
    }

    @Test
    void localModeAloneMapsTheLocalEndpointAndAPlaceholderKey() {
        var env = environment(Map.of("LLM_MODE", "local"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://localhost:11434", env.getProperty("spring.ai.openai.base-url"),
                "Spring AI 1.x appends /v1/chat/completions: the base URL must not carry /v1");
        assertEquals(AtmosphereSpringAiAlibabaEnvironmentPostProcessor.LOCAL_PLACEHOLDER_API_KEY,
                env.getProperty("spring.ai.openai.api-key"));
    }

    /** The sweep's setup: local mode with the placeholder key the docs ask for. */
    @Test
    void localModeWithAPlaceholderKeyStaysLocal() {
        var env = environment(Map.of("llm.mode", "local", "LLM_API_KEY", "ollama"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://localhost:11434", env.getProperty("spring.ai.openai.base-url"));
        assertEquals("ollama", env.getProperty("spring.ai.openai.api-key"));
    }

    @Test
    void anExplicitSpringAiBaseUrlStillWins() {
        var env = environment(Map.of("LLM_MODE", "local", "spring.ai.openai.base-url", "https://dashscope.example"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("https://dashscope.example", env.getProperty("spring.ai.openai.base-url"));
    }

    @Test
    void anExplicitLlmBaseUrlStillWins() {
        var env = environment(Map.of("LLM_MODE", "local", "LLM_BASE_URL", "http://127.0.0.1:8000"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://127.0.0.1:8000", env.getProperty("spring.ai.openai.base-url"));
    }

    /**
     * {@code LLM_BASE_URL} is documented with {@code /v1} for every runtime;
     * passed through, Spring AI 1.x called {@code /v1/v1/chat/completions}, a 404.
     */
    @Test
    void anLlmBaseUrlWithV1IsMappedWithoutIt() {
        var env = environment(Map.of("LLM_MODE", "local", "LLM_BASE_URL", "http://localhost:11434/v1"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://localhost:11434", env.getProperty("spring.ai.openai.base-url"));
    }

    @Test
    void anLlmBaseUrlWithV1AndATrailingSlashIsMappedWithoutBoth() {
        var env = environment(Map.of("llm.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1/"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode", env.getProperty("spring.ai.openai.base-url"),
                "Spring AI 1.x appends /v1/chat/completions to the base URL");
    }

    @Test
    void anExplicitSpringAiBaseUrlWithV1IsNeverRewritten() {
        var env = environment(Map.of("LLM_BASE_URL", "http://localhost:11434/v1",
                "spring.ai.openai.base-url", "https://gateway.example/v1"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("https://gateway.example/v1", env.getProperty("spring.ai.openai.base-url"),
                "the user's own spring.ai.openai.base-url is theirs to get right");
    }

    @Test
    void springAi1BaseUrlStripsOnlyOneTrailingV1() {
        assertEquals("http://localhost:11434",
                AtmosphereSpringAiAlibabaEnvironmentPostProcessor.springAi1BaseUrl(" http://localhost:11434/ "));
        assertEquals("http://h/v1",
                AtmosphereSpringAiAlibabaEnvironmentPostProcessor.springAi1BaseUrl("http://h/v1/v1"));
        assertEquals("http://h/api/v10",
                AtmosphereSpringAiAlibabaEnvironmentPostProcessor.springAi1BaseUrl("http://h/api/v10"));
    }

    /**
     * A blank base URL follows the model, as for the built-in runtime: a
     * Gemini model and key went to Spring AI's default, api.openai.com.
     * Gemini's endpoint carries its own version, so Spring AI 1.x's
     * {@code /v1/...} paths are replaced.
     */
    @Test
    void aGeminiModelWithoutABaseUrlMapsGeminiAndItsPaths() {
        var env = environment(Map.of("GEMINI_API_KEY", "AIza-test", "LLM_MODEL", "gemini-2.5-flash"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals(AiConfig.GEMINI_ENDPOINT, env.getProperty("spring.ai.openai.base-url"));
        assertEquals("/chat/completions", env.getProperty("spring.ai.openai.chat.completions-path"));
        assertEquals("/embeddings", env.getProperty("spring.ai.openai.embedding.embeddings-path"));
    }

    @Test
    void anOpenAiModelWithoutABaseUrlKeepsSpringAisDefaults() {
        var env = environment(Map.of("LLM_API_KEY", "sk-test", "llm.model", "gpt-4o-mini"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertNull(env.getProperty("spring.ai.openai.base-url"));
        assertNull(env.getProperty("spring.ai.openai.chat.completions-path"));
    }

    @Test
    void anExplicitBaseUrlWinsOverTheModel() {
        var env = environment(Map.of("LLM_MODEL", "gemini-2.5-flash", "LLM_BASE_URL", "http://127.0.0.1:8000/v1"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://127.0.0.1:8000", env.getProperty("spring.ai.openai.base-url"));
        assertNull(env.getProperty("spring.ai.openai.chat.completions-path"));
    }

    @Test
    void theModeIsReadTrimmedAndCaseInsensitive() {
        var env = environment(Map.of("LLM_MODE", " Local "));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://localhost:11434", env.getProperty("spring.ai.openai.base-url"));
    }

    @Test
    void localModeWinsOverAGeminiModel() {
        var env = environment(Map.of("LLM_MODE", "local", "LLM_MODEL", "gemini-2.5-flash"));

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertEquals("http://localhost:11434", env.getProperty("spring.ai.openai.base-url"));
    }

    @Test
    void remoteModeWithoutSettingsAddsNothing() {
        var env = environment(Map.of());

        new AtmosphereSpringAiAlibabaEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertFalse(env.getPropertySources().contains(AtmosphereSpringAiAlibabaEnvironmentPostProcessor.SOURCE_NAME),
                "remote mode with nothing configured must not invent an endpoint or a key");
    }

    private static ConfigurableEnvironment environment(Map<String, Object> properties) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return env;
    }
}
