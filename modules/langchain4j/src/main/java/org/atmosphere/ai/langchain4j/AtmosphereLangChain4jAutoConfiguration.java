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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
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
 * Auto-configuration that wires LangChain4j into Atmosphere transparently:
 *
 * <ol>
 *   <li>Builds a default {@link OpenAiStreamingChatModel} from {@code llm.*}
 *       properties when no {@link StreamingChatModel} bean exists (mirrors
 *       Koog/ADK/SK adapter footprints so {@code atmosphere-langchain4j}
 *       works as a transparent dependency swap on {@code spring-boot-ai-chat}).</li>
 *   <li>Bridges whichever {@link StreamingChatModel} bean wins (auto-built
 *       above or user-supplied) into {@link LangChain4jAgentRuntime} so
 *       {@code @AiEndpoint} methods can stream via
 *       {@code session.stream(message)}.</li>
 * </ol>
 *
 * <p>User beans take precedence via {@link ConditionalOnMissingBean} —
 * supplying a custom {@link StreamingChatModel} (Anthropic, Ollama, Bedrock,
 * etc.) overrides the default cleanly.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "dev.langchain4j.model.chat.StreamingChatModel")
public class AtmosphereLangChain4jAutoConfiguration {

    private static final Logger logger =
        LoggerFactory.getLogger(AtmosphereLangChain4jAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(StreamingChatModel.class)
    @ConditionalOnExpression("'${llm.api-key:}' != ''")
    public StreamingChatModel atmosphereLangChain4jStreamingChatModel(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.model:gpt-4o-mini}") String model) {

        logger.info("Auto-building LC4j OpenAiStreamingChatModel model={} endpoint={}", model, baseUrl);
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .build();
    }

    @Bean
    @ConditionalOnBean(StreamingChatModel.class)
    LangChain4jAgentRuntime langChain4jAiSupportBridge(StreamingChatModel model) {
        LangChain4jAgentRuntime.setModel(model);
        return new LangChain4jAgentRuntime();
    }
}
