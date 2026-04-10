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
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.Set;

/**
 * {@link org.atmosphere.ai.AgentRuntime} backed by Spring AI's {@link ChatClient}.
 */
public class SpringAiAgentRuntime extends AbstractAgentRuntime<ChatClient> {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiAgentRuntime.class);

    private static volatile ChatClient staticClient;

    public static void setChatClient(ChatClient client) {
        staticClient = client;
    }

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
        return "Spring AI ChatClient";
    }

    @Override
    protected ChatClient createNativeClient(AiConfig.LlmSettings settings) {
        return staticClient;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (getNativeClient() == null && staticClient != null) {
            setNativeClient(staticClient);
            logger.info("Spring AI auto-configured: model={}, endpoint={}",
                    settings != null ? settings.model() : "default",
                    settings != null ? settings.baseUrl() : "default");
        }
    }

    @Override
    protected void doExecute(ChatClient client, AgentExecutionContext context,
                             StreamingSession session) {
        var promptSpec = client.prompt();

        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            promptSpec = promptSpec.system(context.systemPrompt());
        }
        if (!context.history().isEmpty()) {
            var historyMessages = context.history().stream()
                    .map(SpringAiAgentRuntime::toSpringMessage)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            promptSpec = promptSpec.messages(historyMessages);
        }
        promptSpec = promptSpec.user(context.message());

        if (context.model() != null && !context.model().isBlank()) {
            promptSpec = promptSpec.options(
                    ChatOptions.builder().model(context.model()).build());
            logger.debug("Using per-request model override: {}", context.model());
        }

        var tools = context.tools();
        if (!tools.isEmpty()) {
            var callbacks = SpringAiToolBridge.toToolCallbacks(
                    tools, session, context.approvalStrategy());
            promptSpec = promptSpec.toolCallbacks(callbacks);
        }

        var flux = promptSpec.stream().chatResponse();
        flux.takeWhile(ignored -> !session.isClosed())
                .doOnNext(response -> {
                    if (response.getResult() != null
                            && response.getResult().getOutput() != null) {
                        var text = response.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            // Split large chunks into words for reliable console rendering
                            for (var word : text.split("(?<=\\s)")) {
                                session.send(word);
                            }
                        }
                    }
                    // Report typed token usage if Spring AI surfaced counts. The
                    // default StreamingSession.usage() sink re-emits the legacy
                    // ai.tokens.* metadata keys for existing Micrometer / budget
                    // consumers, so this refactor is wire-compatible.
                    var metadata = response.getMetadata();
                    if (metadata != null && metadata.getUsage() != null) {
                        var u = metadata.getUsage();
                        var tokenUsage = new org.atmosphere.ai.TokenUsage(
                                u.getPromptTokens() != null ? u.getPromptTokens() : 0L,
                                u.getCompletionTokens() != null ? u.getCompletionTokens() : 0L,
                                0L,
                                u.getTotalTokens() != null ? u.getTotalTokens() : 0L,
                                null);
                        if (tokenUsage.hasCounts()) {
                            session.usage(tokenUsage);
                        }
                    }
                })
                .doOnComplete(session::complete)
                .doOnError(error -> {
                    logger.error("Spring AI streaming error: {}", error.getMessage());
                    session.error(error);
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
            case "system" -> new SystemMessage(msg.content());
            case "assistant" -> new AssistantMessage(msg.content());
            default -> new UserMessage(msg.content());
        };
    }
}
