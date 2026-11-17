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
 * that key: there {@link AtmosphereSpringAiAlibabaBoot3EnvironmentListener} runs
 * the same mapping.</p>
 */
public class AtmosphereSpringAiAlibabaEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        SpringAiAlibabaLlmBridge.apply(env);
    }
}
