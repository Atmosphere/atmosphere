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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration that wires AgentScope Java into Atmosphere
 * transparently:
 *
 * <ol>
 *   <li>Builds a default {@link OpenAIChatModel} from {@code llm.*}
 *       properties when no AgentScope {@link Model} bean exists, for a
 *       configured API key or {@code llm.mode=local} (the local Ollama
 *       endpoint, as for the built-in runtime). AgentScope
 *       ships OpenAI-compatible, DashScope, Anthropic, Ollama, and Gemini
 *       chat-model classes; the OpenAI-compatible variant is the right
 *       transparent default for {@code spring-boot-ai-chat} drop-in
 *       replacement (works against OpenAI, Gemini's OpenAI-compat
 *       endpoint, OpenRouter, etc.).</li>
 *   <li>Builds a default {@link ReActAgent} from whichever {@link Model}
 *       bean wins, with a system prompt resolved from
 *       {@code atmosphere.agentscope.system-prompt} (falls back to
 *       {@code llm.system-prompt}).</li>
 *   <li>Bridges the resulting {@link ReActAgent} into
 *       {@link AgentScopeAgentRuntime} so {@code @AiEndpoint} methods
 *       dispatch through it.</li>
 * </ol>
 *
 * <p>User beans take precedence via {@link ConditionalOnMissingBean} —
 * supplying a custom {@link Model} (DashScope, Anthropic, etc.) or a
 * custom {@link ReActAgent} (with tools, memory, hooks) overrides the
 * defaults cleanly.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.agentscope.core.ReActAgent")
public class AtmosphereAgentScopeAutoConfiguration {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereAgentScopeAutoConfiguration.class);

    /**
     * The key a locally served backend (Ollama, vLLM, LM Studio) is sent: it
     * ignores the credential, the OpenAI-compatible client requires one.
     */
    static final String LOCAL_PLACEHOLDER_API_KEY = "not-needed-for-local";

    /**
     * The OpenAI-compatible {@link Model} Atmosphere builds when the
     * application declares none: for a credentialed remote endpoint, or a
     * locally served one.
     *
     * <p>{@code llm.mode=local} ({@code LLM_MODE=local}) is the documented
     * keyless mode: the built-in runtime and the LangChain4j, Koog and
     * Semantic Kernel adapters resolve it to the local Ollama endpoint. This
     * bean read only {@code llm.base-url}, so with the mode alone it sent the
     * user's prompt, and the placeholder key, to api.openai.com. An explicit
     * {@code llm.base-url} ({@code LLM_BASE_URL}) still wins over the mode.</p>
     *
     * @param apiKey  the API key; blank outside local mode builds no model
     * @param baseUrl the explicit endpoint, blank to resolve it from the mode
     * @param model   the model
     * @param mode    {@code llm.mode}; {@code local} selects the local endpoint
     * @return the model
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression(
            "'${llm.api-key:}' != '' or '${llm.mode:}' == 'local' or '${LLM_MODE:}' == 'local'")
    public Model atmosphereAgentScopeOpenAiModel(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.model:gpt-4o-mini}") String model,
            @Value("${llm.mode:${LLM_MODE:}}") String mode) {
        var local = "local".equalsIgnoreCase(mode == null ? null : mode.trim());
        var resolvedBaseUrl = resolveBaseUrl(baseUrl, mode, model);
        var resolvedApiKey = (apiKey == null || apiKey.isBlank()) && local ? LOCAL_PLACEHOLDER_API_KEY : apiKey;
        logger.info("Auto-building AgentScope OpenAIChatModel model={} endpoint={} local={}",
                model, resolvedBaseUrl, local);
        return OpenAIChatModel.builder()
                .apiKey(resolvedApiKey)
                .baseUrl(resolvedBaseUrl)
                .modelName(model)
                .stream(true)
                .build();
    }

    /**
     * The endpoint for {@code explicitBaseUrl}, {@code mode} and {@code model},
     * resolved as the built-in runtime resolves it
     * ({@link AiConfig#resolveBaseUrl(String, String, String)}): an explicit URL
     * wins, else the local Ollama endpoint in local mode, else the provider the
     * model names ({@code gpt-*}, {@code o1*}, {@code o3*}: OpenAI; any other
     * model: Gemini's OpenAI-compatible endpoint). A blank URL used to mean
     * OpenAI whatever the model, so a Gemini model and key went to
     * api.openai.com.
     */
    static String resolveBaseUrl(String explicitBaseUrl, String mode, String model) {
        var explicit = explicitBaseUrl == null || explicitBaseUrl.isBlank() ? null : explicitBaseUrl.trim();
        return AiConfig.resolveBaseUrl(mode == null ? null : mode.trim(), explicit, model);
    }

    @Bean
    @ConditionalOnMissingBean(ReActAgent.class)
    @ConditionalOnBean(Model.class)
    public ReActAgent atmosphereAgentScopeReActAgent(
            Model model,
            @Value("${atmosphere.agentscope.system-prompt:${llm.system-prompt:You are a helpful AI assistant.}}") String systemPrompt) {
        logger.info("Auto-building AgentScope ReActAgent with model={}", model.getModelName());
        return ReActAgent.builder()
                .name("atmosphere-agentscope")
                .sysPrompt(systemPrompt)
                .model(model)
                .build();
    }

    @Bean
    @ConditionalOnBean(ReActAgent.class)
    // Without an AiEnvironment bean in the context the process-wide binder is the only one.
    @SuppressWarnings("deprecation")
    AgentScopeAgentRuntime agentScopeAgentRuntime(ReActAgent agent,
            ObjectProvider<AiEnvironment> environmentProvider) {
        // Bound to this context's AiEnvironment when there is one (the
        // Atmosphere starter registers it), so another context in the JVM
        // keeps its own agent; else to the JVM, as before.
        var environment = environmentProvider.getIfAvailable();
        if (environment != null) {
            AgentScopeAgentRuntime.setAgent(environment, agent);
        } else {
            AgentScopeAgentRuntime.setAgent(agent);
        }
        return new AgentScopeAgentRuntime();
    }
}
