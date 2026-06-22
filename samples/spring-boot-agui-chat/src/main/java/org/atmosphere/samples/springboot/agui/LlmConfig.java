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
package org.atmosphere.samples.springboot.agui;

import org.atmosphere.ai.AiConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resolves the LLM settings for the AG-UI assistant. Declaring the {@code @Bean}
 * is enough: {@link AiConfig#configure} stores the resolved settings in the
 * framework-wide singleton that {@link AiConfig#get()} returns, which the agent's
 * {@code @Prompt} method reads to decide between the real pipeline and the demo
 * fallback.
 *
 * <p>With no {@code llm.api-key} (the default), {@code AiConfig.get().apiKey()}
 * is {@code null} and the sample runs in demo mode. Set {@code LLM_API_KEY} /
 * {@code llm.api-key} (and optionally {@code llm.model} / {@code llm.mode}) to
 * drive a real model and exercise {@code @AiTool} dispatch.</p>
 */
@Configuration
public class LlmConfig {

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.model:gemini-2.5-flash}") String model,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl) {
        return AiConfig.configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }
}
