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
package org.atmosphere.samples.springboot.skchat;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import com.microsoft.semantickernel.aiservices.openai.chatcompletion.OpenAIChatCompletion;
import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import org.atmosphere.ai.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges Spring properties into:
 * <ul>
 *   <li>The Atmosphere {@link AiConfig} shared holder (so the demo-mode
 *       fallback in {@link SkChat#onPrompt} can detect a missing key).</li>
 *   <li>A real Semantic Kernel {@link ChatCompletionService} bean, built
 *       from an Azure {@link OpenAIAsyncClient} via
 *       {@link OpenAIChatCompletion#builder()} using SK 1.4.0's public API.
 *       {@code AtmosphereSemanticKernelAutoConfiguration} then wires that
 *       bean into {@code SemanticKernelAgentRuntime} on context refresh.</li>
 * </ul>
 *
 * <p>The {@link ChatCompletionService} bean is only declared when an API
 * key is present — without one there is nothing for SK to call, and the
 * sample falls through to {@link DemoResponseProducer} so it still works
 * out-of-the-box.</p>
 */
@Configuration
public class SkConfig {

    private static final Logger logger = LoggerFactory.getLogger(SkConfig.class);

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gpt-4o-mini}") String model) {

        return AiConfig.configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }

    /**
     * Builds an Azure OpenAI async client against either the default
     * {@code api.openai.com} endpoint or a user-supplied {@code llm.base-url}
     * (e.g. for Azure OpenAI or a compatible proxy).
     *
     * <p>Uses the real Azure SDK API:
     * {@code new OpenAIClientBuilder().credential(new KeyCredential(apiKey))
     *     .endpoint(url).buildAsyncClient()}.</p>
     */
    @Bean
    @ConditionalOnExpression("'${llm.api-key:}' != ''")
    public OpenAIAsyncClient openAIAsyncClient(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl) {

        logger.info("Building SK OpenAIAsyncClient endpoint={}", baseUrl);
        return new OpenAIClientBuilder()
                .credential(new KeyCredential(apiKey))
                .endpoint(baseUrl)
                .buildAsyncClient();
    }

    /**
     * Assembles the SK {@link ChatCompletionService} with the configured
     * model ID. Uses SK 1.4.0's real builder surface:
     * {@code OpenAIChatCompletion.builder().withOpenAIAsyncClient(client)
     *     .withModelId(model).build()}. This bean triggers
     * {@code AtmosphereSemanticKernelAutoConfiguration}'s
     * {@code @ConditionalOnBean} wiring into the runtime.
     */
    @Bean
    @ConditionalOnExpression("'${llm.api-key:}' != ''")
    public ChatCompletionService chatCompletionService(
            OpenAIAsyncClient client,
            @Value("${llm.model:gpt-4o-mini}") String model) {

        logger.info("Building SK ChatCompletionService modelId={}", model);
        return OpenAIChatCompletion.builder()
                .withOpenAIAsyncClient(client)
                .withModelId(model)
                .build();
    }
}
