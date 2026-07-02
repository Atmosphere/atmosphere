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

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.AbstractOpenAiOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for the Spring AI agent runtime.
 * Creates a {@link ChatClient} bean from environment config when no other
 * Spring AI auto-configuration provides one (e.g., Spring Boot 4.0 where
 * Spring AI 2.0 autoconfig is not yet compatible).
 *
 * <p>Spring AI 2.0 (M5+) routes all OpenAI traffic through the official
 * {@code com.openai:openai-java} SDK. {@code OpenAiChatModel$Builder.build()}
 * requires <strong>both</strong> a sync ({@link OpenAIClient}) and an async
 * ({@link OpenAIClientAsync}) OpenAI client; when only one is provided the
 * constructor falls back to {@code OpenAiSetup.setupAsyncClient(null, ...)}
 * which throws {@code IllegalStateException("At least one credential source
 * must be specified")}. We mirror the canonical
 * {@code OpenAiChatAutoConfiguration#openAiChatModel} pattern by building
 * both clients through {@link OpenAiSetup}.</p>
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
            @Value("${spring.ai.openai.chat.options.model:${LLM_MODEL:gpt-4o-mini}}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            logger.info("No API key configured — Spring AI ChatClient not created");
            return null;
        }
        // OpenAi SDK Kotlin builders reject null Duration/int for timeout/maxRetries;
        // use the SDK's published defaults rather than guessing values.
        // Spring AI 2.0.0 GA added three trailing params to OpenAiSetup
        // (observation registry, meter registry, http-client customizers).
        // Pass no-op values to preserve the M6 behaviour (no external
        // observability wiring); customizers list is empty.
        OpenAIClient syncClient = OpenAiSetup.setupSyncClient(
                baseUrl, apiKey, null, null, null, null, false, false, model,
                AbstractOpenAiOptions.DEFAULT_TIMEOUT, AbstractOpenAiOptions.DEFAULT_MAX_RETRIES,
                null, Map.<String, String>of(),
                ObservationRegistry.NOOP, new SimpleMeterRegistry(), List.of());
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                baseUrl, apiKey, null, null, null, null, false, false, model,
                AbstractOpenAiOptions.DEFAULT_TIMEOUT, AbstractOpenAiOptions.DEFAULT_MAX_RETRIES,
                null, Map.<String, String>of(),
                ObservationRegistry.NOOP, new SimpleMeterRegistry(), List.of());
        var chatModel = OpenAiChatModel.builder()
                .openAiClient(syncClient)
                .openAiClientAsync(asyncClient)
                .options(OpenAiChatOptions.builder().model(model).build())
                .build();
        logger.info("Spring AI ChatClient auto-configured: model={}, endpoint={}", model, baseUrl);
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    @ConditionalOnMissingBean(SpringAiAgentRuntime.class)
    public SpringAiAgentRuntime springAiAgentRuntime(ObjectProvider<ChatClient> chatClientProvider) {
        var chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            logger.info("No ChatClient available — Spring AI AgentRuntime runs in demo mode");
            return new SpringAiAgentRuntime();
        }
        // Offer, never bind: an application that already called
        // setChatClient(...) (e.g. a caller-built client carrying
        // defaultAdvisors) owns the binding; the context bean is only the
        // default when no explicit binding exists.
        SpringAiAgentRuntime.offerChatClient(chatClient);
        return new SpringAiAgentRuntime();
    }

    @Bean
    @ConditionalOnMissingBean(SpringAiEmbeddingRuntime.class)
    public SpringAiEmbeddingRuntime springAiEmbeddingRuntime(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        var embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel != null) {
            SpringAiEmbeddingRuntime.setEmbeddingModel(embeddingModel);
        }
        return new SpringAiEmbeddingRuntime();
    }
}
