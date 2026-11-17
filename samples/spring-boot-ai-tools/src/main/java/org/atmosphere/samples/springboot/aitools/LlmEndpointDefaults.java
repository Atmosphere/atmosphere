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
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fills a blank {@code llm.mode} and {@code llm.model} with
 * {@link AiConfig#DEFAULT_MODE} and {@link AiConfig#DEFAULT_MODEL}, then a blank
 * {@code llm.base-url} with the endpoint
 * {@link AiConfig#resolveBaseUrl(String, String, String)} derives from them:
 * Ollama for {@code local}, OpenAI for a {@code gpt-*}/{@code o1}/{@code o3}
 * model, Gemini otherwise.
 *
 * <p>{@code application.yml} leaves {@code LLM_MODE}/{@code LLM_MODEL} without
 * a default so {@code atmosphere.ai.mode}/{@code model} can reach the adapters
 * (the starter maps them onto a blank {@code llm.*} only). The adapters read
 * {@code ${llm.model:...}}, whose fallback does not apply to an empty string,
 * so a model nobody set is filled here rather than left blank.</p>
 *
 * <p>The adapters' own auto-configurations read {@code llm.base-url} as given
 * and fall back to OpenAI's endpoint when it is blank, so with only a
 * {@code GEMINI_API_KEY} set they would send the Gemini key to OpenAI. The
 * sample used to avoid that by building a LangChain4j model itself, which tied
 * its sources to LangChain4j: {@code atmosphere new --template ai-tools
 * --runtime <other> --force} removes the LangChain4j dependency and the
 * project no longer compiled. Resolving the endpoint here, before any
 * auto-configuration runs, keeps the sample free of framework types and gives
 * every adapter the endpoint {@link AiConfig} resolves.</p>
 *
 * <p>Runs after {@code application.yml} is loaded and after the starter's
 * {@code AtmosphereAiLlmPropertyBridge}, which maps {@code atmosphere.ai.mode},
 * {@code model}, {@code api-key} and {@code base-url} onto a blank
 * {@code llm.*}: run before it, a blank {@code LLM_MODE}/{@code LLM_MODEL} with
 * {@code atmosphere.ai.mode=local} derived the Gemini endpoint and a local run
 * was sent to Google. It still runs before the adapters' own post-processors,
 * which declare no order and read {@code llm.*}. It acts only when the
 * property is blank, so an explicit {@code LLM_BASE_URL} always wins.</p>
 *
 * <p>Registered under the {@code org.springframework.boot.EnvironmentPostProcessor}
 * key of {@code META-INF/spring.factories}, the only one Spring Boot 4.2 reads.
 * Spring Boot 3 has neither that type nor that key: built with
 * {@code -Pspring-boot3}, the sample leaves this class out and
 * {@link LlmEndpointDefaultsBoot3Listener} applies the same defaults.</p>
 */
public class LlmEndpointDefaults implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        LlmEndpointDefaultsSupport.apply(environment);
    }

    /** See {@link LlmEndpointDefaultsSupport#ORDER}. */
    @Override
    public int getOrder() {
        return LlmEndpointDefaultsSupport.ORDER;
    }
}
