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
package org.atmosphere.samples.springboot.langchain4jchat;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.atmosphere.ai.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration that creates a LangChain4j {@link StreamingChatLanguageModel}
 * and bridges settings into {@link AiConfig}.
 */
@Configuration
public class LlmConfig {

    private static final Logger logger = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gemini-2.5-flash}") String model) {

        return AiConfig.configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }

    @Bean
    public StreamingChatLanguageModel streamingChatModel(AiConfig.LlmSettings settings) {
        logger.info("Creating LangChain4j StreamingChatLanguageModel: model={}, endpoint={}",
                settings.model(), settings.baseUrl());

        return OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.client().apiKey())
                .modelName(settings.model())
                .build();
    }
}
