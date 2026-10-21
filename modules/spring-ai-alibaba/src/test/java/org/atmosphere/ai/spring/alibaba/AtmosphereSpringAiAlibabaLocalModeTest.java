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

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
