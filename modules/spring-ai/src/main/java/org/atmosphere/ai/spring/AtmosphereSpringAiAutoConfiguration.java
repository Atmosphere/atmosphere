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
import org.springframework.beans.factory.ObjectProvider;
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
            @Value("${spring.ai.openai.api-key:${LLM_API_KEY:}}") String apiKey,
            @Value("${spring.ai.openai.base-url:${LLM_BASE_URL:https://api.openai.com}}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:${LLM_MODEL:gpt-4o-mini}}") String model,
            @Value("${spring.ai.openai.chat.completions-path:}") String completionsPathOverride) {
        if (apiKey == null || apiKey.isBlank()) {
            logger.info("No API key configured — Spring AI ChatClient not created");
            return null;
        }
        var apiBuilder = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl);
        // Spring AI defaults completions path to "/v1/chat/completions". When the
        // base URL already encodes the API path prefix (Gemini's OpenAI-compat
        // ".../v1beta/openai", Anthropic's ".../v1", etc.), the default produces
        // ".../v1beta/openai/v1/chat/completions" which 400s. Strip the leading
        // "/v1" for these endpoints so the natural URL forms correctly. An
        // explicit spring.ai.openai.chat.completions-path always wins.
        String completionsPath = !completionsPathOverride.isBlank()
                ? completionsPathOverride
                : resolveCompletionsPath(baseUrl);
        if (completionsPath != null) {
            apiBuilder.completionsPath(completionsPath);
        }
        var api = apiBuilder.build();
        var chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
        logger.info("Spring AI ChatClient auto-configured: model={}, endpoint={}, completionsPath={}",
                model, baseUrl, completionsPath != null ? completionsPath : "(default /v1/chat/completions)");
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Choose a completions path for {@link OpenAiApi.Builder#completionsPath}
     * based on the base URL. Returns {@code null} to keep Spring AI's
     * default ({@code /v1/chat/completions}) for OpenAI proper or unknown
     * endpoints; returns {@code /chat/completions} when the base URL already
     * encodes a vendored API path (Gemini's {@code /v1beta/openai}, Azure
     * OpenAI's deployment URLs).
     */
    static String resolveCompletionsPath(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // Gemini OpenAI-compat: https://generativelanguage.googleapis.com/v1beta/openai
        if (trimmed.endsWith("/v1beta/openai")) {
            return "/chat/completions";
        }
        // Generic heuristic: any base URL ending in "/openai" already carries a
        // vendor-specific API prefix; the canonical OpenAI proxy convention is
        // to expose chat completions directly under that segment.
        if (trimmed.endsWith("/openai")) {
            return "/chat/completions";
        }
        return null;
    }

    @Bean
    @ConditionalOnMissingBean(SpringAiAgentRuntime.class)
    public SpringAiAgentRuntime springAiAgentRuntime(ObjectProvider<ChatClient> chatClientProvider) {
        var chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            logger.info("No ChatClient available — Spring AI AgentRuntime runs in demo mode");
            return new SpringAiAgentRuntime();
        }
        SpringAiAgentRuntime.setChatClient(chatClient);
        return new SpringAiAgentRuntime();
    }
}
