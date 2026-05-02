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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *       properties when no AgentScope {@link Model} bean exists. AgentScope
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

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${llm.api-key:}' != ''")
    public Model atmosphereAgentScopeOpenAiModel(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.model:gpt-4o-mini}") String model) {
        var resolvedBaseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "https://api.openai.com/v1"
                : baseUrl;
        logger.info("Auto-building AgentScope OpenAIChatModel model={} endpoint={}", model, resolvedBaseUrl);
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(resolvedBaseUrl)
                .modelName(model)
                .stream(true)
                .build();
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
    AgentScopeAgentRuntime agentScopeAgentRuntime(ReActAgent agent) {
        AgentScopeAgentRuntime.setAgent(agent);
        return new AgentScopeAgentRuntime();
    }
}
