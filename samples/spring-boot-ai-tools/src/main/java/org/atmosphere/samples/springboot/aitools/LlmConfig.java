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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM configuration for the AI tools sample.
 *
 * <p>Bridges the {@code llm.*} properties into Atmosphere's {@link AiConfig}.
 * It declares no client of any AI framework: the adapter on the classpath
 * ({@code atmosphere-langchain4j} in this pom) builds its own from the same
 * {@code llm.*} properties, so the backend can be swapped by changing the
 * Maven dependency (for example {@code atmosphere new --template ai-tools
 * --runtime spring-ai --force}) without changing any code. The endpoint for a
 * blank {@code llm.base-url} is resolved once, by {@link LlmEndpointDefaults},
 * so every adapter sees the same one.</p>
 */
@Configuration
public class LlmConfig {

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.model:gemini-2.5-flash}") String model,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl) {
        return AiConfig.configure(mode, model, apiKey, baseUrl);
    }
}
