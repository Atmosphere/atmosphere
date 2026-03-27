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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Spring AI agent runtime.
 * Creates a {@link ChatClient} bean from environment config when no other
 * Spring AI auto-configuration provides one (e.g., Spring Boot 4.0 where
 * Spring AI 2.0 autoconfig is not yet compatible).
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
public class AtmosphereSpringAiAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereSpringAiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SpringAiStreamingAdapter springAiStreamingAdapter() {
        return new SpringAiStreamingAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient atmosphereChatClient(
            @Value("${spring.ai.openai.api-key:${LLM_API_KEY:${GEMINI_API_KEY:}}}") String apiKey,
            @Value("${spring.ai.openai.base-url:${LLM_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai}}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:${LLM_MODEL:gemini-2.5-flash}}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            logger.debug("No API key for Spring AI ChatClient — skipping");
            return null;
        }
        var api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        var chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
        logger.info("Spring AI ChatClient auto-configured: model={}, endpoint={}", model, baseUrl);
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    @ConditionalOnMissingBean(SpringAiAgentRuntime.class)
    public SpringAiAgentRuntime springAiAgentRuntime(ChatClient chatClient) {
        SpringAiAgentRuntime.setChatClient(chatClient);
        return new SpringAiAgentRuntime();
    }
}
