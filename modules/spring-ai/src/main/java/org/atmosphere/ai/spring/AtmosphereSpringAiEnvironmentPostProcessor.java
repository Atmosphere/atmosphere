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

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Bridges Atmosphere's {@code llm.*} property convention into the
 * {@code spring.ai.openai.*} namespace expected by Spring AI's own
 * auto-configuration. Runs as an {@link EnvironmentPostProcessor} so the
 * mapped values land in the {@link ConfigurableEnvironment} <em>before</em>
 * Spring AI's {@code @AutoConfiguration} classes are evaluated.
 *
 * <p>{@code AtmosphereSpringAiAutoConfiguration} already builds a
 * {@link org.springframework.ai.chat.client.ChatClient} from {@code llm.*},
 * but Spring AI 2.0 ships several sibling auto-configurations
 * ({@code OpenAiAudioSpeechAutoConfiguration},
 * {@code OpenAiAudioTranscriptionAutoConfiguration},
 * {@code OpenAiImageAutoConfiguration},
 * {@code OpenAiEmbeddingAutoConfiguration}) which read
 * {@code spring.ai.openai.api-key} directly and fail bean construction with
 * {@code "OpenAI API key must be set"} when only {@code llm.api-key} is
 * configured. This bridge eliminates that startup failure so the dep-swap
 * stays transparent.</p>
 *
 * <p>Mappings (each only applied when the source value is set and the target
 * is not already configured by the user):</p>
 * <ul>
 *   <li>{@code llm.api-key} → {@code spring.ai.openai.api-key}</li>
 *   <li>{@code llm.base-url} → {@code spring.ai.openai.base-url}; with none,
 *       {@code llm.mode=local} ({@code LLM_MODE=local}) maps the local Ollama
 *       endpoint and, when no key is set, a placeholder key</li>
 *   <li>with neither, a model that is not OpenAI's ({@code gemini-*}, ...) maps
 *       the endpoint the built-in runtime derives from it
 *       ({@code AiConfig.resolveBaseUrl}), Gemini's OpenAI-compatible one</li>
 *   <li>{@code llm.model}    → {@code spring.ai.openai.chat.options.model}</li>
 * </ul>
 *
 * <p>The property source registered here has the lowest precedence among
 * Atmosphere-supplied sources, so users who set the {@code spring.ai.openai.*}
 * properties directly always win.</p>
 *
 * <p>When the bridge maps the key or the endpoint, a real key goes over plain
 * {@code http} only to a loopback host: with any other {@code http} host the
 * application fails to start (logged at {@code ERROR}) unless it sets
 * {@value org.atmosphere.ai.llm.CleartextApiKeyPolicy#ALLOW_PROPERTY}{@code =true}
 * ({@code CleartextApiKeyPolicy}).</p>
 *
 * <p>Registered under the {@code org.springframework.boot.EnvironmentPostProcessor}
 * key of {@code META-INF/spring.factories}, the only one Spring Boot 4.2 reads;
 * Spring Boot 4.0 and 4.1 read it too. Spring Boot 3 has neither that type nor
 * that key: there {@link AtmosphereSpringAiBoot3EnvironmentListener} runs the
 * same mapping.</p>
 */
public class AtmosphereSpringAiEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        SpringAiLlmBridge.apply(env);
    }
}
