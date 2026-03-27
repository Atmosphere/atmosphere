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

import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
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
import java.util.Set;


/**
 * {@link org.atmosphere.ai.AiSupport} implementation backed by Spring AI's {@link ChatClient}.
 *
 * <p>Auto-detected when {@code spring-ai-client-chat} is on the classpath.
 * The {@link ChatClient} must be configured via {@link #setChatClient} — typically
 * done by {@link AtmosphereSpringAiAutoConfiguration}.</p>
 */
public class SpringAiAgentRuntime extends AbstractAgentRuntime<ChatClient> {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiAgentRuntime.class);

    @Override
    public String name() {
        return "spring-ai";
    }

    @Override
    protected String nativeClientClassName() {
        return "org.springframework.ai.chat.client.ChatClient";
    }

    @Override
    protected String clientDescription() {
        return "ChatClient";
    }

    @Override
    protected String configurationHint() {
        return "Ensure spring-ai-openai or another Spring AI model starter is on the classpath.";
    }

    @Override
    protected ChatClient createNativeClient(AiConfig.LlmSettings settings) {
        var apiKey = settings.client().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            Class.forName("org.springframework.ai.openai.OpenAiChatModel");
        } catch (ClassNotFoundException e) {
            logger.info("spring-ai-openai not on classpath; add it or provide a ChatClient bean");
            return null;
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
        var client = ChatClient.create(chatModel);
        logger.info("Spring AI auto-configured: model={}, endpoint={}", settings.model(), settings.baseUrl());
        return client;
    }

    /**
     * Set the {@link ChatClient} to use for streaming. Called by the
     * Spring auto-configuration.
     */
    public static void setChatClient(ChatClient client) {
        // Static setter for Spring auto-configuration compatibility.
        // Creates a temporary instance to set the client on the singleton resolved
        // by ServiceLoader. In practice, the auto-configuration creates the bean
        // and sets it before any streaming occurs.
        staticClient = client;
    }

    // Held for static setter compatibility with Spring auto-configuration
    private static volatile ChatClient staticClient;

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // If a static client was set via Spring auto-configuration, use it
        if (getNativeClient() == null && staticClient != null) {
            setNativeClient(staticClient);
        }
        super.configure(settings);
    }

    @Override
    protected void doExecute(ChatClient client, AgentExecutionContext context, StreamingSession session) {
        session.progress("Connecting to AI model...");

        var promptSpec = client.prompt();
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            promptSpec = promptSpec.system(context.systemPrompt());
        }
        // Insert conversation history between system prompt and current user message
        if (!context.history().isEmpty()) {
            var historyMessages = new ArrayList<Message>();
            for (var historyMsg : context.history()) {
                historyMessages.add(toSpringMessage(historyMsg));
            }
            promptSpec = promptSpec.messages(historyMessages);
        }
        promptSpec = promptSpec.user(context.message());

        // Register tool callbacks if tools are present
        var tools = context.tools();
        if (!tools.isEmpty()) {
            var callbacks = SpringAiToolBridge.toToolCallbacks(tools);
            promptSpec = promptSpec.toolCallbacks(callbacks);
            logger.debug("Registered {} tool callbacks with Spring AI", callbacks.size());
        }

        Flux<ChatResponse> flux = promptSpec.stream().chatResponse();
        flux.takeWhile(ignored -> !session.isClosed())
                .doOnNext(response -> {
                    if (response.getResult() != null
                            && response.getResult().getOutput() != null
                            && response.getResult().getOutput().getText() != null) {
                        session.send(response.getResult().getOutput().getText());
                    }
                })
                .doOnComplete(session::complete)
                .doOnError(error -> {
                    session.error(error);
                    logger.debug("Streaming error for session {}", session.sessionId(), error);
                })
                .blockLast();
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
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
