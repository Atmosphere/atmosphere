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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration that wires Spring AI Alibaba into
 * Atmosphere transparently:
 *
 * <ol>
 *   <li>Builds a default {@link ReactAgent} from whichever Spring AI
 *       {@link ChatModel} bean is on the context (typically supplied by
 *       {@code spring-ai-starter-model-openai} via the {@code llm.*}
 *       properties), with a system prompt resolved from
 *       {@code atmosphere.spring-ai-alibaba.system-prompt} (falls back to
 *       {@code llm.system-prompt}).</li>
 *   <li>Bridges the resulting {@link ReactAgent} into
 *       {@link SpringAiAlibabaAgentRuntime}.</li>
 * </ol>
 *
 * <p>User beans take precedence via {@link ConditionalOnMissingBean} —
 * supplying a custom {@link ReactAgent} with tools, sub-agents, or a
 * graph compile config overrides the default cleanly.</p>
 */
// @AutoConfiguration ordering: this autoconfig must run AFTER Spring AI's
// OpenAI autoconfig has had a chance to create the ChatModel bean.
// @ConditionalOnBean(ChatModel.class) evaluates against beans that already
// exist at evaluation time, so without an explicit afterName our ReactAgent
// factory gets evaluated first and misses the ChatModel — the debug
// auto-configuration report then shows
// "did not find any beans of type ChatModel" while OpenAiChatAutoConfiguration
// itself reports "matched" further down. Naming the upstream class by FQN
// avoids dragging spring-ai-autoconfigure-model-openai into our compile
// classpath; the string is resolved by Spring Boot's auto-config infrastructure.
@AutoConfiguration(afterName = "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration")
@ConditionalOnClass(name = "com.alibaba.cloud.ai.graph.agent.ReactAgent")
public class AtmosphereSpringAiAlibabaAutoConfiguration {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereSpringAiAlibabaAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ReactAgent.class)
    @ConditionalOnBean(ChatModel.class)
    public ReactAgent atmosphereSpringAiAlibabaReactAgent(
            ChatModel chatModel,
            @Value("${atmosphere.spring-ai-alibaba.system-prompt:${llm.system-prompt:You are a helpful AI assistant.}}") String systemPrompt) {
        logger.info("Auto-building Spring AI Alibaba ReactAgent with chatModel={}",
                chatModel.getClass().getSimpleName());
        try {
            return ReactAgent.builder()
                    .name("atmosphere-spring-ai-alibaba")
                    .model(chatModel)
                    .systemPrompt(systemPrompt)
                    .build();
        } catch (Exception e) {
            // ReactAgent.Builder.build() throws GraphStateException — wrap
            // as IllegalStateException so the bean creation failure surfaces
            // through Spring's normal startup error path.
            throw new IllegalStateException("Failed to build Spring AI Alibaba ReactAgent", e);
        }
    }

    @Bean
    @ConditionalOnBean(ReactAgent.class)
    SpringAiAlibabaAgentRuntime springAiAlibabaAgentRuntime(ReactAgent agent) {
        SpringAiAlibabaAgentRuntime.setAgent(agent);
        return new SpringAiAlibabaAgentRuntime();
    }
}
