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

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.atmosphere.ai.AiConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM configuration for the AI tools sample.
 *
 * <p>Configures the LangChain4j streaming model and Atmosphere's {@link AiConfig}.
 * The LLM backend can be swapped by changing the Maven dependency
 * (e.g., replace {@code atmosphere-langchain4j} with {@code atmosphere-spring-ai})
 * without changing any tool code.</p>
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

    @Bean
    public StreamingChatModel streamingChatModel(AiConfig.LlmSettings settings) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .modelName(settings.model())
                .build();
    }
}
