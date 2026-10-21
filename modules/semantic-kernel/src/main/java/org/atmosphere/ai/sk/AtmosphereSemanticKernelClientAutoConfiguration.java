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
import org.atmosphere.ai.AiConfig;
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

    /**
     * Build a client when there is something to talk to — a credentialed remote
     * endpoint, or a locally served one.
     *
     * <p>The gate used to be an API key alone, and the base URL defaulted to
     * OpenAI. A local backend (Ollama, vLLM, LM Studio) needs no credential, so
     * with {@code llm.mode=local} no client was built at all and Semantic Kernel
     * could never serve a keyless local deployment — the same defect already
     * fixed in the LangChain4j, Koog and built-in paths. A missing key says
     * nothing about whether a model is reachable.</p>
     *
     * <p>{@code LLM_MODE} is honoured alongside {@code llm.mode} because several
     * samples configure the backend entirely through the environment and declare
     * no {@code llm} block at all.</p>
     *
     * <p>An API key goes over plain {@code http} only to a loopback host: with a
     * key and an {@code http} base URL on any other host the application fails
     * to start, unless it sets
     * {@value SemanticKernelOpenAiClientFactory#ALLOW_CLEARTEXT_API_KEY_PROPERTY}
     * to {@code true} (see
     * {@link SemanticKernelOpenAiClientFactory#forEndpoint(String, String, boolean)}).</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(
            "'${llm.api-key:}' != '' or '${llm.mode:}'.trim().equalsIgnoreCase('local') "
            + "or '${LLM_MODE:}'.trim().equalsIgnoreCase('local')")
    public OpenAIAsyncClient atmosphereSemanticKernelOpenAIAsyncClient(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.mode:}") String mode,
            @Value("${LLM_MODE:}") String environmentMode,
            @Value("${llm.model:gpt-4o-mini}") String model,
            @Value("${" + SemanticKernelOpenAiClientFactory.ALLOW_CLEARTEXT_API_KEY_PROPERTY + ":}")
            String allowCleartextApiKey) {

        // Trimmed and case-insensitive, as the gate above and AiConfig read it.
        var local = "local".equalsIgnoreCase(mode == null ? null : mode.trim())
                || "local".equalsIgnoreCase(environmentMode == null ? null : environmentMode.trim());

        // Spring's @Value default only applies when the property is absent; an
        // empty value still binds. Resolve the fallback here so a blank
        // llm.base-url cannot send a local run, or a Gemini model, to OpenAI.
        var resolvedBaseUrl = resolveBaseUrl(baseUrl, local, model);

        // A blank key reaches the factory as keyless: it sends a placeholder the
        // local server ignores, and lets that call go over plain http to any
        // host, since it carries no secret. A real key goes over plain http
        // only to a loopback host, unless the application opts in.
        logger.info("Auto-building SK OpenAIAsyncClient endpoint={} local={} model={}", resolvedBaseUrl, local, model);
        return SemanticKernelOpenAiClientFactory.forEndpoint(resolvedBaseUrl, apiKey,
                // Only "true" opts in: blank, "false" or anything else keeps the refusal.
                Boolean.parseBoolean(allowCleartextApiKey.strip()));
    }

    /**
     * The endpoint for {@code baseUrl}, the mode and {@code model}, resolved as
     * the built-in runtime resolves it
     * ({@link AiConfig#resolveBaseUrl(String, String, String)}): an explicit URL
     * wins (with a WARN when it overrides local mode with a remote host), else
     * the local Ollama endpoint in local mode, else the provider the model names
     * ({@code gpt-*}, {@code o1*}, {@code o3*}: OpenAI; any other model:
     * Gemini's OpenAI-compatible endpoint). A blank URL used to mean OpenAI
     * whatever the model, so a Gemini model and key went to api.openai.com.
     */
    static String resolveBaseUrl(String baseUrl, boolean local, String model) {
        var explicit = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
        return AiConfig.resolveBaseUrl(local ? "local" : "remote", explicit, model);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(
            "'${llm.api-key:}' != '' or '${llm.mode:}'.trim().equalsIgnoreCase('local') "
            + "or '${LLM_MODE:}'.trim().equalsIgnoreCase('local')")
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
