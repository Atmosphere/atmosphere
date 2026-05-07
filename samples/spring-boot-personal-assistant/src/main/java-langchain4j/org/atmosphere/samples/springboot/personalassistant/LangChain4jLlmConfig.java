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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.ai.AiConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j {@code StreamingChatModel} wiring. Active only when
 * {@code dev.langchain4j.model.openai.OpenAiStreamingChatModel} is on the
 * classpath — i.e. when the user activated the {@code runtime-langchain4j}
 * Maven profile. Without that profile the class isn't compiled into the
 * deployable, so the {@code @ConditionalOnClass} guard makes the bean a
 * no-op even on the default classpath.
 *
 * <p>The actual model construction is in a nested method-handle-like helper
 * to keep the class itself loadable even when LangChain4j is absent — the
 * {@code @ConditionalOnClass} decision is made before any method body runs.</p>
 */
@Configuration
@ConditionalOnClass(name = "dev.langchain4j.model.openai.OpenAiStreamingChatModel")
public class LangChain4jLlmConfig {

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.model:gemini-2.5-flash}") String model,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl) {
        return AiConfig.configure(mode, model, apiKey, baseUrl);
    }

    @Bean
    public dev.langchain4j.model.chat.StreamingChatModel streamingChatModel(AiConfig.LlmSettings settings) {
        return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .modelName(settings.model())
                .build();
    }
}
