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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.atmosphere.ai.llm.ChatMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.ArrayList;

/**
 * {@link AiSupport} implementation backed by Spring AI's {@link ChatClient}.
 *
 * <p>Auto-detected when {@code spring-ai-client-chat} is on the classpath.
 * The {@link ChatClient} must be configured via {@link #setChatClient} — typically
 * done by {@link AtmosphereSpringAiAutoConfiguration}.</p>
 */
public class SpringAiSupport implements AiSupport {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiSupport.class);

    private static volatile ChatClient chatClient;

    @Override
    public String name() {
        return "spring-ai";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.springframework.ai.chat.client.ChatClient");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (chatClient != null) {
            return;
        }

        var apiKey = settings.client().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }

        try {
            Class.forName("org.springframework.ai.openai.OpenAiChatModel");
        } catch (ClassNotFoundException e) {
            logger.info("spring-ai-openai not on classpath; add it or provide a ChatClient bean");
            return;
        }

        var api = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(apiKey)
                .build();
        var options = org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model(settings.model())
                .build();
        var chatModel = org.springframework.ai.openai.OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
        chatClient = ChatClient.create(chatModel);
        logger.info("Spring AI auto-configured: model={}, endpoint={}", settings.model(), settings.baseUrl());
    }

    /**
     * Set the {@link ChatClient} to use for streaming. Called by the
     * Spring auto-configuration.
     */
    public static void setChatClient(ChatClient client) {
        chatClient = client;
    }

    @Override
    public void stream(AiRequest request, StreamingSession session) {
        var client = chatClient;
        if (client == null) {
            var settings = AiConfig.get();
            if (settings == null) {
                settings = AiConfig.fromEnvironment();
            }
            configure(settings);
            client = chatClient;
        }
        if (client == null) {
            throw new IllegalStateException(
                    "SpringAiSupport: ChatClient not configured. "
                            + "Ensure spring-ai-openai or another Spring AI model starter is on the classpath.");
        }

        session.progress("Connecting to AI model...");

        var promptSpec = client.prompt();
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            promptSpec = promptSpec.system(request.systemPrompt());
        }
        // Insert conversation history between system prompt and current user message
        if (!request.history().isEmpty()) {
            var historyMessages = new ArrayList<Message>();
            for (var historyMsg : request.history()) {
                historyMessages.add(toSpringMessage(historyMsg));
            }
            promptSpec = promptSpec.messages(historyMessages);
        }
        promptSpec = promptSpec.user(request.message());

        // Register tool callbacks if tools are present
        var tools = request.tools();
        if (!tools.isEmpty()) {
            var callbacks = SpringAiToolBridge.toToolCallbacks(tools);
            promptSpec = promptSpec.toolCallbacks(callbacks);
            logger.debug("Registered {} tool callbacks with Spring AI", callbacks.size());
        }

        Flux<ChatResponse> flux = promptSpec.stream().chatResponse();
        flux.doOnNext(response -> {
                    if (response.getResult() != null
                            && response.getResult().getOutput() != null
                            && response.getResult().getOutput().getText() != null) {
                        session.send(response.getResult().getOutput().getText());
                    }
                })
                .doOnComplete(session::complete)
                .doOnError(session::error)
                .subscribe();
    }

    @Override
    public java.util.Set<AiCapability> capabilities() {
        return java.util.Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT
        );
    }

    private static Message toSpringMessage(ChatMessage msg) {
        return switch (msg.role()) {
            case "assistant" -> new AssistantMessage(msg.content());
            case "system" -> new SystemMessage(msg.content());
            default -> new UserMessage(msg.content());
        };
    }
}
