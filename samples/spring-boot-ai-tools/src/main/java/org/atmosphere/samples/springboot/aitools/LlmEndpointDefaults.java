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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Fills a blank {@code llm.base-url} with the endpoint
 * {@link AiConfig#resolveBaseUrl(String, String, String)} derives from
 * {@code llm.mode} and {@code llm.model}: Ollama for {@code local}, OpenAI for
 * a {@code gpt-*}/{@code o1}/{@code o3} model, Gemini otherwise (this sample's
 * default model is {@code gemini-2.5-flash}).
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
 * property is blank, so an explicit {@code LLM_BASE_URL} always wins.
 * Registered in {@code META-INF/spring.factories}.</p>
 */
// org.springframework.boot.env.EnvironmentPostProcessor is deprecated in Spring
// Boot 4, which moved it to org.springframework.boot; the old type and its
// spring.factories key are the ones both Boot 3 (this sample's spring-boot3
// profile) and Boot 4 load, as the framework's own post-processors do.
@SuppressWarnings({"deprecation", "removal"})
public class LlmEndpointDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "ai-tools-llm-endpoint-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var baseUrl = environment.getProperty("llm.base-url");
        if (baseUrl != null && !baseUrl.isBlank()) {
            return;
        }
        var resolved = AiConfig.resolveBaseUrl(
                environment.getProperty("llm.mode", "remote"), null,
                environment.getProperty("llm.model", "gemini-2.5-flash"));
        // First, not last: application.yml declares llm.base-url as an empty
        // string, which would shadow a lower-precedence source. Only a blank
        // value reaches this line, so nothing a user set is overridden.
        environment.getPropertySources().addFirst(
                new MapPropertySource(SOURCE_NAME, Map.of("llm.base-url", resolved)));
    }

    /**
     * After {@code AtmosphereAiLlmPropertyBridge} ({@code LOWEST_PRECEDENCE - 10}),
     * before a post-processor with no order ({@code LOWEST_PRECEDENCE}). A literal:
     * the bridge's constant is package-private in {@code org.atmosphere.spring.boot}.
     */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 5;

    @Override
    public int getOrder() {
        return ORDER;
    }
}
