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
import org.atmosphere.ai.AiEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
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

    /** The context's {@link AiEnvironment}; {@code null} when built by hand. */
    private final ObjectProvider<AiEnvironment> environmentProvider;

    /** An instance that binds through the runtimes' static binders, as before environments existed. */
    public AtmosphereSpringAiAlibabaAutoConfiguration() {
        this(null);
    }

    /**
     * @param environmentProvider the context's {@link AiEnvironment} (the
     *                            Atmosphere starter registers one): the
     *                            context's {@code ReactAgent}, {@code ChatModel}
     *                            and {@code EmbeddingModel} are bound to it, so
     *                            they serve this context only
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AtmosphereSpringAiAlibabaAutoConfiguration(ObjectProvider<AiEnvironment> environmentProvider) {
        this.environmentProvider = environmentProvider;
    }

    private AiEnvironment aiEnvironment() {
        return environmentProvider != null ? environmentProvider.getIfAvailable() : null;
    }

    // Without an AiEnvironment bean in the context the process-wide binder is the only one.
    @SuppressWarnings("deprecation")
    private void bindChatModel(ChatModel chatModel) {
        var environment = aiEnvironment();
        if (environment != null) {
            SpringAiAlibabaAgentRuntime.setChatModel(environment, chatModel);
        } else {
            SpringAiAlibabaAgentRuntime.setChatModel(chatModel);
        }
    }

    @Bean
    @ConditionalOnMissingBean(ReactAgent.class)
    @ConditionalOnBean(ChatModel.class)
    public ReactAgent atmosphereSpringAiAlibabaReactAgent(
            ChatModel chatModel,
            @Value("${atmosphere.spring-ai-alibaba.system-prompt:${llm.system-prompt:You are a helpful AI assistant.}}") String systemPrompt) {
        logger.info("Auto-building Spring AI Alibaba ReactAgent with chatModel={}",
                chatModel.getClass().getSimpleName());
        try {
            // Wrap once so every dispatch path — singleton agent OR per-request
            // tool agent — pushes ChatResponse.getMetadata().getUsage() into the
            // per-thread accumulator the runtime reads for TOKEN_USAGE.
            var captured = wrap(chatModel);
            bindChatModel(captured);
            return ReactAgent.builder()
                    .name("atmosphere-spring-ai-alibaba")
                    .model(captured)
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
    // Without an AiEnvironment bean in the context the process-wide binder is the only one.
    @SuppressWarnings("deprecation")
    SpringAiAlibabaAgentRuntime springAiAlibabaAgentRuntime(
            ReactAgent agent, ObjectProvider<ChatModel> chatModelProvider) {
        var environment = aiEnvironment();
        if (environment != null) {
            SpringAiAlibabaAgentRuntime.setAgent(environment, agent);
        } else {
            SpringAiAlibabaAgentRuntime.setAgent(agent);
        }
        var chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null) {
            // User-supplied ReactAgent path: we still wrap their ChatModel so
            // the per-request tool agent we build in doExecute captures usage.
            // The user's singleton ReactAgent may have been built with the
            // unwrapped bean — that singleton path will not emit TOKEN_USAGE
            // for tool-free calls; documented in SpringAiAlibabaAgentRuntime
            // class javadoc.
            bindChatModel(wrap(chatModel));
        }
        return new SpringAiAlibabaAgentRuntime();
    }

    private static ChatModel wrap(ChatModel raw) {
        return raw instanceof UsageCapturingChatModel ? raw : new UsageCapturingChatModel(raw);
    }

    @Bean
    @ConditionalOnMissingBean(SpringAiAlibabaEmbeddingRuntime.class)
    // Without an AiEnvironment bean in the context the process-wide binder is the only one.
    @SuppressWarnings("deprecation")
    SpringAiAlibabaEmbeddingRuntime springAiAlibabaEmbeddingRuntime(
            ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        var embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel != null) {
            var environment = aiEnvironment();
            if (environment != null) {
                SpringAiAlibabaEmbeddingRuntime.setEmbeddingModel(environment, embeddingModel);
            } else {
                SpringAiAlibabaEmbeddingRuntime.setEmbeddingModel(embeddingModel);
            }
        }
        return new SpringAiAlibabaEmbeddingRuntime();
    }
}
