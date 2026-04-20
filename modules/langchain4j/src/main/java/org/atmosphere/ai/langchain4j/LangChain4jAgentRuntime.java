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
        var handle = doExecuteWithHandle(streamingModel, context, session);
        try {
            handle.whenDone().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // Error already surfaced via session.error() from the handler
        }
    }

    @Override
    protected org.atmosphere.ai.ExecutionHandle doExecuteWithHandle(
            StreamingChatModel streamingModel,
            AgentExecutionContext context, StreamingSession session) {
        // Admit through the process-wide AiGateway before issuing the native
        // LangChain4j dispatch — uniform per-user rate limiting and credential
        // resolution across all seven runtimes (Correctness Invariant #3).
        admitThroughGateway(context);
        var messages = assembleMessages(context).stream()
                .map(LangChain4jAgentRuntime::toLangChainMessage)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // Translate any multi-modal Content.Image / Content.Audio parts on
        // the context into LC4j-native Content instances and replace the
        // trailing user message (which assembleMessages put there as plain
        // text) with a rich UserMessage carrying text + media. LC4j's
        // ImageContent has no direct byte[] factory, so binary inputs are
        // base64-encoded and passed to ImageContent.from(String base64,
        // String mimeType) — same pattern for AudioContent.
        if (!context.parts().isEmpty()) {
            var mediaContents = new ArrayList<dev.langchain4j.data.message.Content>();
            mediaContents.add(dev.langchain4j.data.message.TextContent.from(context.message()));
            for (var part : context.parts()) {
                if (part instanceof org.atmosphere.ai.Content.Image img) {
                    mediaContents.add(dev.langchain4j.data.message.ImageContent.from(
                            img.dataBase64(), img.mimeType()));
                } else if (part instanceof org.atmosphere.ai.Content.Audio audio) {
                    mediaContents.add(dev.langchain4j.data.message.AudioContent.from(
                            audio.dataBase64(), audio.mimeType()));
                }
                // Content.Text folded into the text block above; Content.File
                // has no LC4j user-message equivalent (file inputs typically
                // go through tool calling instead of message media).
            }
            if (mediaContents.size() > 1) {
                // Replace the last assembled user message (plain text) with
                // the multi-content variant. assembleMessages always puts the
                // user message last, so messages.size()-1 is the right index.
                if (!messages.isEmpty()) {
                    messages.remove(messages.size() - 1);
                }
                messages.add(dev.langchain4j.data.message.UserMessage.from(mediaContents));
            }
        }

        // Add tool specifications if tools are present
        var tools = context.tools();
        var toolSpecs = tools.isEmpty()
                ? List.<dev.langchain4j.agent.tool.ToolSpecification>of()
                : LangChain4jToolBridge.toToolSpecifications(tools);

        var chatRequestBuilder = ChatRequest.builder().messages(messages);

        // Prompt-caching: the Atmosphere CacheHint translates to an
        // OpenAI-path {@code prompt_cache_key} injected via LC4j's
        // OpenAiChatRequestParameters.customParameters(Map) — LC4j's generic
        // ChatRequest.Builder has no typed cache accessor, so we go through
        // the OpenAI params surface. Non-OpenAI providers silently ignore
        // the field.
        var cacheHint = org.atmosphere.ai.llm.CacheHint.from(context);
        var cacheKey = cacheHint.resolvedKey(context);
        if (cacheHint.enabled() && cacheKey.isPresent()) {
            var paramsBuilder = dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder()
                    .customParameters(java.util.Map.of("prompt_cache_key", cacheKey.get()));
            if (context.model() != null && !context.model().isBlank()) {
                paramsBuilder.modelName(context.model());
            }
            chatRequestBuilder.parameters(paramsBuilder.build());
            logger.debug("Applied prompt_cache_key={} via OpenAiChatRequestParameters", cacheKey.get());
        } else if (context.model() != null && !context.model().isBlank()) {
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
                        context.approvalStrategy(), context.listeners(), cancelled,
                        context.approvalPolicy()),
                cancelled, done);
        streamingModel.chat(chatRequestBuilder.build(), handler);

        return new org.atmosphere.ai.ExecutionHandle() {
            @Override public void cancel() {
                cancelled.set(true);
                // Resolve the future as cancelled so doExecute()'s
                // handle.whenDone().get() unblocks immediately. The LC4j
                // handler's CancelAwareStreamingHandler drops remaining
                // tokens via the cancelled flag; LC4j's internal HTTP
                // thread drains naturally. On JDK HttpClient providers,
                // the VT interrupt from get()'s CancellationException
                // propagates to blocking I/O and kills the connection.
                if (!done.isDone()) {
                    done.completeExceptionally(
                            new java.util.concurrent.CancellationException("cancelled"));
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
            // LC4j's HTTP transport may surface a late SocketException /
            // IOException after the caller has already cancelled — the
            // underlying connection unwinds asynchronously on the provider's
            // worker thread. Forwarding that error into the (already
            // abandoned) session would fire a phantom AiEvent.Error after
            // the session was explicitly cancelled. Drop post-cancel errors
            // and leave the done future resolved as it was set by cancel().
            var wasCancelled = cancelled.get();
            try {
                if (!wasCancelled) {
                    inner.onError(error);
                } else {
                    logger.trace("Dropping post-cancel LC4j error", error);
                }
            } finally {
                if (!wasCancelled) {
                    done.completeExceptionally(error);
                }
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
                AiCapability.TOOL_APPROVAL,
                // VISION / AUDIO / MULTI_MODAL are honest: doExecuteWithHandle
                // translates Content.Image and Content.Audio parts into LC4j
                // ImageContent / AudioContent attached to a multi-content
                // UserMessage. Underlying model support depends on the
                // configured StreamingChatModel — pointing Atmosphere at a
                // text-only model will surface a provider-level error at
                // dispatch, not a silent drop.
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL,
                // PROMPT_CACHING is honest on the OpenAI path: doExecuteWithHandle
                // reads a CacheHint from context.metadata() and attaches
                // {@code prompt_cache_key} through
                // OpenAiChatRequestParameters.customParameters. Non-OpenAI
                // providers silently drop the key.
                AiCapability.PROMPT_CACHING,
                // TOKEN_USAGE: ToolAwareStreamingResponseHandler reads the
                // tokenUsage() off the LC4j ChatResponse onComplete and emits
                // a typed TokenUsage record via session.usage() — see
                // ToolAwareStreamingResponseHandler.java:128-136.
                // CONVERSATION_MEMORY: AbstractAgentRuntime.assembleMessages
                // threads context.history() into the LC4j ChatRequest, so
                // the pipeline-managed history is honored on every request.
                AiCapability.TOKEN_USAGE,
                AiCapability.CONVERSATION_MEMORY,
                // PER_REQUEST_RETRY: honored via AbstractAgentRuntime's
                // outer retry wrapper (executeWithOuterRetry). Retries
                // pre-stream transient failures on top of LC4j's own
                // RetryUtils layer.
                AiCapability.PER_REQUEST_RETRY
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
