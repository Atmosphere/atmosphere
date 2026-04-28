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
package org.atmosphere.ai.sk;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.microsoft.semantickernel.aiservices.openai.chatcompletion.OpenAIChatCompletion;
import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Default auto-built {@link OpenAIAsyncClient} and {@link ChatCompletionService}
 * beans for {@link SemanticKernelAgentRuntime}. Fires when {@code llm.api-key}
 * is set and the application has not already supplied its own beans, removing
 * the need for sample applications to write the ~80 lines of SK client
 * boilerplate manually. Mirrors the auto-configuration footprint of the other
 * runtime adapters (Koog, ADK, LangChain4j) so {@code atmosphere-semantic-kernel}
 * works as a drop-in dependency swap on {@code spring-boot-ai-chat}.
 *
 * <p>User beans take precedence via {@link ConditionalOnMissingBean} — supplying
 * a custom {@link ChatCompletionService} (e.g. for Azure AD auth, custom HTTP
 * pipeline, or a non-OpenAI SK service) overrides this default cleanly.</p>
 *
 * <p>The wiring of whichever {@link ChatCompletionService} bean ends up in the
 * context (auto-built here or user-supplied) into the runtime's static setter
 * happens in {@link AtmosphereSemanticKernelAutoConfiguration}, which is
 * ordered to run after this configuration.</p>
 */
@AutoConfiguration
@ConditionalOnClass(ChatCompletionService.class)
public class AtmosphereSemanticKernelClientAutoConfiguration {

    private static final Logger logger =
        LoggerFactory.getLogger(AtmosphereSemanticKernelClientAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${llm.api-key:}' != ''")
    public OpenAIAsyncClient atmosphereSemanticKernelOpenAIAsyncClient(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl) {

        logger.info("Auto-building SK OpenAIAsyncClient endpoint={}", baseUrl);
        return SemanticKernelOpenAiClientFactory.forEndpoint(baseUrl, apiKey);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${llm.api-key:}' != ''")
    public ChatCompletionService atmosphereSemanticKernelChatCompletionService(
            OpenAIAsyncClient client,
            @Value("${llm.model:gpt-4o-mini}") String model) {

        logger.info("Auto-building SK ChatCompletionService modelId={}", model);
        return OpenAIChatCompletion.builder()
                .withOpenAIAsyncClient(client)
                .withModelId(model)
                .build();
    }
}
