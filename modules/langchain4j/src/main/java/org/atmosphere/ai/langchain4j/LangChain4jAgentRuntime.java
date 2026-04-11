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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link org.atmosphere.ai.AiSupport} implementation backed by LangChain4j's
 * {@link StreamingChatModel}.
 *
 * <p>Auto-detected when {@code langchain4j-core} is on the classpath.
 * The model must be configured via {@link #setModel} — typically done
 * by application configuration or Spring auto-configuration.</p>
 */
public class LangChain4jAgentRuntime extends AbstractAgentRuntime<StreamingChatModel> {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jAgentRuntime.class);

    @Override
    public String name() {
        return "langchain4j";
    }

    @Override
    protected String nativeClientClassName() {
        return "dev.langchain4j.model.chat.StreamingChatModel";
    }

    @Override
    protected String clientDescription() {
        return "StreamingChatModel";
    }

    @Override
    protected String configurationHint() {
        return "Call LangChain4jAgentRuntime.setModel() or use Spring auto-configuration.";
    }

    @Override
    protected StreamingChatModel createNativeClient(AiConfig.LlmSettings settings) {
        var apiKey = settings.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            Class.forName("dev.langchain4j.model.openai.OpenAiStreamingChatModel");
        } catch (ClassNotFoundException e) {
            logger.info("langchain4j-open-ai not on classpath; add it or call setModel() manually");
            return null;
        }

        // Eagerly link langchain4j HTTP response types from the main thread.
        // JdkHttpClient.fromJdkResponse references SuccessfulHttpResponse only on the success
        // branch — if the first request fails (e.g. upstream 503), the class never gets linked,
        // then a later success on a ForkJoinPool worker thread hits NoClassDefFoundError because
        // Spring Boot's nested-jar LaunchedClassLoader can't resolve it from that calling context.
        eagerLoad("dev.langchain4j.http.client.SuccessfulHttpResponse");
        eagerLoad("dev.langchain4j.http.client.SuccessfulHttpResponse$Builder");
        eagerLoad("dev.langchain4j.http.client.HttpException");

        var model = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(apiKey)
                .modelName(settings.model())
                .build();
        logger.info("LangChain4j auto-configured: model={}, endpoint={}", settings.model(), settings.baseUrl());
        return model;
    }

    private static void eagerLoad(String className) {
        try {
            Class.forName(className, true, LangChain4jAgentRuntime.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.debug("Eager class-load skipped for {}: {}", className, e.getMessage());
        }
    }

    /**
     * Set the {@link StreamingChatModel} to use for streaming.
     */
    public static void setModel(StreamingChatModel streamingModel) {
        staticModel = streamingModel;
    }

    // Held for static setter compatibility with Spring auto-configuration
    private static volatile StreamingChatModel staticModel;

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // If a static model was set via Spring auto-configuration, use it
        if (getNativeClient() == null && staticModel != null) {
            setNativeClient(staticModel);
        }
        super.configure(settings);
    }

    @Override
    protected void doExecute(StreamingChatModel streamingModel,
                            AgentExecutionContext context, StreamingSession session) {
        doExecuteWithHandle(streamingModel, context, session);
    }

    @Override
    protected org.atmosphere.ai.ExecutionHandle doExecuteWithHandle(
            StreamingChatModel streamingModel,
            AgentExecutionContext context, StreamingSession session) {
        var messages = assembleMessages(context).stream()
                .map(LangChain4jAgentRuntime::toLangChainMessage)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // Add tool specifications if tools are present
        var tools = context.tools();
        var toolSpecs = tools.isEmpty()
                ? List.<dev.langchain4j.agent.tool.ToolSpecification>of()
                : LangChain4jToolBridge.toToolSpecifications(tools);

        var chatRequestBuilder = ChatRequest.builder().messages(messages);
        if (context.model() != null && !context.model().isBlank()) {
            chatRequestBuilder.modelName(context.model());
            logger.debug("Using per-request model override: {}", context.model());
        }
        if (!toolSpecs.isEmpty()) {
            chatRequestBuilder.toolSpecifications(toolSpecs);
            logger.debug("Registered {} tool specifications with LangChain4j", toolSpecs.size());
        }

        var toolMap = tools.isEmpty()
                ? java.util.Map.<String, org.atmosphere.ai.tool.ToolDefinition>of()
                : ToolExecutionHelper.toToolMap(tools);

        // D-6 LC4j native cancel: the StreamingChatResponseHandler has no
        // direct HTTP cancel, so we thread a soft-cancel flag through the
        // handler. cancel() flips the flag; onPartialResponse polls it on
        // every token and stops forwarding once set. Cancellation takes
        // effect at the next token boundary — not immediate, but avoids
        // leaking completions into an abandoned session.
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var done = new java.util.concurrent.CompletableFuture<Void>();

        var handler = new CancelAwareStreamingHandler(
                new ToolAwareStreamingResponseHandler(
                        session, streamingModel, messages, toolSpecs, toolMap,
                        context.approvalStrategy(), cancelled),
                cancelled, done);
        streamingModel.chat(chatRequestBuilder.build(), handler);

        return new org.atmosphere.ai.ExecutionHandle() {
            @Override public void cancel() {
                cancelled.set(true);
                // Best-effort mark: if the handler is still polling, it will
                // drop the next onPartialResponse; if the stream is already
                // done, this is a no-op.
                if (!done.isDone()) {
                    done.complete(null);
                }
            }
            @Override public boolean isDone() { return done.isDone(); }
            @Override public java.util.concurrent.CompletableFuture<Void> whenDone() { return done; }
        };
    }

    /**
     * Wraps an inner {@link dev.langchain4j.model.chat.response.StreamingChatResponseHandler}
     * so the done-future completes exactly once on terminal events
     * ({@code onCompleteResponse} or {@code onError}), regardless of whether
     * the inner handler chose to continue into a tool round or finalize.
     */
    private static final class CancelAwareStreamingHandler
            implements dev.langchain4j.model.chat.response.StreamingChatResponseHandler {
        private final dev.langchain4j.model.chat.response.StreamingChatResponseHandler inner;
        private final java.util.concurrent.atomic.AtomicBoolean cancelled;
        private final java.util.concurrent.CompletableFuture<Void> done;

        CancelAwareStreamingHandler(
                dev.langchain4j.model.chat.response.StreamingChatResponseHandler inner,
                java.util.concurrent.atomic.AtomicBoolean cancelled,
                java.util.concurrent.CompletableFuture<Void> done) {
            this.inner = inner;
            this.cancelled = cancelled;
            this.done = done;
        }

        @Override public void onPartialResponse(String partial) {
            if (!cancelled.get()) {
                inner.onPartialResponse(partial);
            }
        }

        @Override public void onCompleteResponse(
                dev.langchain4j.model.chat.response.ChatResponse response) {
            try {
                if (!cancelled.get()) {
                    inner.onCompleteResponse(response);
                }
            } finally {
                done.complete(null);
            }
        }

        @Override public void onError(Throwable error) {
            try {
                inner.onError(error);
            } finally {
                done.completeExceptionally(error);
            }
        }
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT,
                // TOOL_APPROVAL is honest: ToolAwareStreamingResponseHandler
                // routes tool invocation through
                // ToolExecutionHelper.executeWithApproval, so @RequiresApproval
                // gates fire uniformly with the other runtime bridges.
                AiCapability.TOOL_APPROVAL
        );
    }

    @Override
    public java.util.List<String> models() {
        // LC4j's StreamingChatModel hides the configured model name behind
        // provider-specific builders. Report the runtime-resolved default
        // from AiConfig; per-request overrides via context.model() take
        // precedence when present.
        var settings = AiConfig.get();
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            return java.util.List.of();
        }
        return java.util.List.of(settings.model());
    }

    private static dev.langchain4j.data.message.ChatMessage toLangChainMessage(
            org.atmosphere.ai.llm.ChatMessage msg) {
        return switch (msg.role()) {
            case "assistant" -> AiMessage.from(msg.content());
            case "system" -> SystemMessage.from(msg.content());
            default -> UserMessage.from(msg.content());
        };
    }
}
