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
package org.atmosphere.samples.springboot.a2astartup;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-activates when Spring AI is on the classpath. Creates the ChatClient
 * bean that {@code SpringAiAgentRuntime} needs. Spring AI 2.0.0-M2 autoconfig
 * is not yet compatible with Spring Boot 4.0, so we wire manually.
 *
 * <p>Dormant when Spring AI JARs are not present — {@code @ConditionalOnClass}
 * prevents loading.</p>
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.ai.chat.client.ChatClient")
public class SpringAiConfig {

    @Bean
    public ChatClient chatClient(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        var api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        var chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
        return ChatClient.builder(chatModel).build();
    }
}
