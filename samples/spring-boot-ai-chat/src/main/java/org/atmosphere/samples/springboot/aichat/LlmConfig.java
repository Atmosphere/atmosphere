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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.AiConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration that bridges Spring's {@code @Value} properties
 * into the framework-level {@link AiConfig}.
 */
@Configuration
public class LlmConfig {

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gemini-2.5-flash}") String model) {

        return AiConfig.configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }
}
