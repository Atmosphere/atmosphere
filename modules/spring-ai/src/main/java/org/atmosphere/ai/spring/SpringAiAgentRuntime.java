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
import org.atmosphere.ai.ExecutionHandle;
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
        // Legacy blocking path: delegate to the cancellation-aware variant and
        // block on whenDone() so the contract (return after completion) holds.
        var handle = doExecuteWithHandle(client, context, session);
        try {
            handle.whenDone().join();
        } catch (Exception e) {
            // whenDone() completes normally even on error (session.error fires),
            // so any exception here is unexpected. Ensure the session closes.
            if (!session.isClosed()) {
                session.error(e);
            }
        }
    }

    @Override
    protected ExecutionHandle doExecuteWithHandle(
            ChatClient client, AgentExecutionContext context, StreamingSession session) {
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

        // Phase 2: wrap the Reactor Disposable in an ExecutionHandle so callers
        // can cancel in-flight Spring AI completions. The Settable helper holds
        // the CompletableFuture<Void> we complete on any terminal path (next,
        // error, complete, cancel) so consumers can await release cleanly.
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        var disposable = flux.takeWhile(ignored -> !session.isClosed())
                .doOnNext(response -> {
                    if (response.getResult() != null
                            && response.getResult().getOutput() != null) {
                        var text = response.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            for (var word : text.split("(?<=\\s)")) {
                                session.send(word);
                            }
                        }
                    }
                    // Phase 1: typed token usage event.
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
                .doOnComplete(() -> {
                    session.complete();
                    completion.complete(null);
                })
                .doOnError(error -> {
                    logger.error("Spring AI streaming error: {}", error.getMessage());
                    session.error(error);
                    completion.complete(null);
                })
                .doOnCancel(() -> completion.complete(null))
                .subscribe();

        return new ExecutionHandle() {
            private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    disposable.dispose();
                    if (!session.isClosed()) {
                        session.complete();
                    }
                    completion.complete(null);
                }
            }

            @Override
            public boolean isDone() {
                return completion.isDone();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> whenDone() {
                return completion;
            }
        };
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
