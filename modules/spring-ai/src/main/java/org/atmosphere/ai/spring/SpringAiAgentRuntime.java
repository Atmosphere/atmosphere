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

    /**
     * Binds the {@link ChatClient} every Atmosphere AI request dispatches
     * through. This is the application-facing binder: calling it marks an
     * explicit client binding, which (a) makes the resolver prefer this
     * runtime over the no-key demo fallback — a bound client serves without
     * any API key — and (b) protects the binding from being clobbered by
     * auto-configuration, which must go through {@link #offerChatClient}.
     */
    public static void setChatClient(ChatClient client) {
        staticClient = client;
        if (client != null) {
            org.atmosphere.ai.AgentRuntimeResolver.markExplicitClientBinding();
        }
    }

    /**
     * Auto-configuration seam: binds {@code client} only when the
     * application has not already bound one. Auto-detected context beans
     * must never overwrite an explicit {@link #setChatClient} call — the
     * binder owns the binding (that clobbering is exactly how a bound
     * client's {@code defaultAdvisors(...)} silently stopped firing).
     */
    public static void offerChatClient(ChatClient client) {
        if (staticClient == null && client != null) {
            setChatClient(client);
        }
    }

    /** The currently bound client, if any. Package-private for tests. */
    static ChatClient boundClient() {
        return staticClient;
    }

    /** Test hook: drops the bound client so each test starts unbound. */
    static void clearChatClientBinding() {
        staticClient = null;
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
        // Admit through the process-wide AiGateway before issuing the native
        // Spring AI dispatch — uniform per-user rate limiting and credential
        // resolution across all twelve contract-tested runtimes. A rejected admission throws
        // (Correctness Invariant #3 — never ignore backpressure).
        admitThroughGateway(context);
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

        // Translate any multi-modal Content.Image / Content.Audio parts on the
        // context into Spring AI Media attached to the user message. Text-only
        // paths keep the legacy fast path so existing callers don't pay the
        // Consumer<PromptUserSpec> overhead. ByteArrayResource wraps the raw
        // bytes because Spring AI 1.1's Media constructor takes a
        // Resource, not a byte[].
        var mediaList = new ArrayList<org.springframework.ai.content.Media>();
        for (var part : context.parts()) {
            if (part instanceof org.atmosphere.ai.Content.Image img) {
                var mimeType = org.springframework.util.MimeType.valueOf(img.mimeType());
                var resource = new org.springframework.core.io.ByteArrayResource(img.data());
                mediaList.add(new org.springframework.ai.content.Media(mimeType, resource));
            } else if (part instanceof org.atmosphere.ai.Content.Audio audio) {
                var mimeType = org.springframework.util.MimeType.valueOf(audio.mimeType());
                var resource = new org.springframework.core.io.ByteArrayResource(audio.data());
                mediaList.add(new org.springframework.ai.content.Media(mimeType, resource));
            }
            // Content.Text is folded into the main message below; Content.File
            // has no Spring AI equivalent on the user-message path (file inputs
            // are typically attached via tool-calling, not as message media).
        }
        if (mediaList.isEmpty()) {
            promptSpec = promptSpec.user(context.message());
        } else {
            var messageText = context.message();
            var mediaArray = mediaList.toArray(new org.springframework.ai.content.Media[0]);
            promptSpec = promptSpec.user(u -> u.text(messageText).media(mediaArray));
        }

        // Prompt-caching: if the context carries a CacheHint, build
        // OpenAiChatOptions instead so we can set both the model and
        // promptCacheKey in one options instance. OpenAiChatOptions extends
        // ChatOptions and is merged by Spring AI's ChatClient whenever the
        // underlying ChatModel is OpenAI-backed.
        // CacheHint.endpointAcceptsPromptCacheKey is the shared default-DENY
        // allow-list (api.openai.com / Azure OpenAI / loopback only); on every
        // other host — including a strict OpenAI-compat proxy that would reject
        // the unknown field — the injection is suppressed and the generic
        // ChatOptions path is kept.
        var settings = org.atmosphere.ai.AiConfig.get();
        var endpointUrl = settings != null ? settings.baseUrl() : null;
        // Tri-state override: ENABLED force-emits, DISABLED force-suppresses,
        // AUTO (default) defers to the shared allow-list, so the framework and
        // Built-in runtimes make the identical AUTO decision (Mode Parity).
        var cacheKeyMode = settings != null
                ? settings.promptCacheKeyMode()
                : org.atmosphere.ai.llm.PromptCacheKeyMode.AUTO;
        var endpointAccepts = cacheKeyMode.resolve(
                org.atmosphere.ai.llm.CacheHint.endpointAcceptsPromptCacheKey(endpointUrl));
        var cacheHint = org.atmosphere.ai.llm.CacheHint.from(context);
        var cacheKey = cacheHint.resolvedKey(context);
        // Framework-level generation overrides (temperature/maxTokens/topP/stop).
        // Applied to whatever options object this dispatch builds — Spring AI's
        // generic ChatOptions.Builder carries temperature/maxTokens/topP/
        // stopSequences, so all four reach the wire on the OpenAI-backed path.
        var generation = settings != null
                ? settings.generation() : org.atmosphere.ai.GenerationParams.defaults();
        // Provider-native structured output: the pipeline stamps the apply flag
        // and the generated JSON Schema when the request declares a response type
        // and NativeStructuredOutputMode is not DISABLED. Only the OpenAI-backed
        // OpenAiChatOptions builder carries the outputSchema(...) setter; on a
        // non-OpenAI ChatModel Spring AI ignores the OpenAI-specific options, so
        // the schema is harmlessly dropped (the prompt-injection path still
        // carries it). On the OpenAI path a rejected schema trips the
        // NativeStructuredOutputMode.AUTO graceful fall-back (the pipeline
        // re-dispatches with the apply flag cleared, so nativeSchema becomes null
        // and this falls back to the generic/no-options path).
        var nativeSchema = context.responseType() != null
                && org.atmosphere.ai.NativeStructuredOutput.shouldApply(context)
                ? org.atmosphere.ai.NativeStructuredOutput.schema(context) : null;
        var cacheApplies = cacheHint.enabled() && cacheKey.isPresent() && endpointAccepts;
        if (cacheApplies || nativeSchema != null) {
            // OpenAiChatOptions carries BOTH the prompt-cache extraBody escape
            // hatch and the outputSchema(...) native structured-output setter.
            // Spring AI 2.0 (M5+) replaced OpenAiChatOptions.Builder.promptCacheKey(String)
            // with the generic extraBody(Map) escape hatch so OpenAI extension fields
            // (prompt_cache_key, store, service_tier, ...) ride through without
            // per-field builder methods. ChatClient.options() also now takes a
            // ChatOptions.Builder<?> rather than the built options.
            var openAiOpts = org.springframework.ai.openai.OpenAiChatOptions.builder();
            if (cacheApplies) {
                openAiOpts.extraBody(java.util.Map.of("prompt_cache_key", cacheKey.get()));
            }
            if (nativeSchema != null) {
                // outputSchema(String) auto-selects strict JSON_SCHEMA on the
                // OpenAI path (Spring AI sets the SDK ResponseFormat strict flag).
                openAiOpts.outputSchema(nativeSchema);
                logger.debug("Applied native structured-output schema (model={})", context.model());
            }
            if (context.model() != null && !context.model().isBlank()) {
                openAiOpts.model(context.model());
            }
            applyGeneration(openAiOpts, generation);
            promptSpec = promptSpec.options(openAiOpts);
            if (cacheApplies) {
                logger.debug("Applied prompt_cache_key={} (model={})",
                        cacheKey.get(), context.model());
            }
        } else if ((context.model() != null && !context.model().isBlank()) || generation.hasAny()) {
            // A per-request model override OR any framework generation override
            // needs an options object. The generic ChatOptions.Builder carries
            // both; when only generation is set we still build it (no model()
            // call) so the override reaches the wire without forcing a model.
            var opts = ChatOptions.builder();
            if (context.model() != null && !context.model().isBlank()) {
                opts.model(context.model());
                logger.debug("Using per-request model override: {}", context.model());
            }
            applyGeneration(opts, generation);
            promptSpec = promptSpec.options(opts);
        }

        var tools = context.tools();
        if (!tools.isEmpty()) {
            var callbacks = SpringAiToolBridge.toToolCallbacks(
                    tools, session, context.approvalStrategy(), context.listeners(),
                    context.approvalPolicy());
            // Spring AI 2.0.0 GA: every toolCallbacks(...) overload is deprecated
            // for removal; the replacement is the generic tools(Object...), which
            // accepts ToolCallback instances directly.
            promptSpec = promptSpec.tools(callbacks.toArray());
        }

        // Per-request Spring AI Advisor chain: caller attaches via
        // SpringAiAdvisors.attach(context, advisor...) or via an interceptor
        // that stamps the metadata key. Empty list = caller did not opt in;
        // promptSpec keeps whatever ChatClient.Builder.defaultAdvisors(...)
        // installed at bean construction. Spring AI's per-request advisors
        // are appended to the builder defaults, matching the same additive
        // semantics SpringAiAdvisors.attach honors at the helper layer.
        var perRequestAdvisors = SpringAiAdvisors.from(context);
        if (!perRequestAdvisors.isEmpty()) {
            promptSpec = promptSpec.advisors(perRequestAdvisors);
            logger.debug("Attached {} per-request Spring AI Advisor(s)",
                    perRequestAdvisors.size());
        }

        var flux = promptSpec.stream().chatResponse();

        // Model-lifecycle hooks: fire onModelStart/End/Error so consumers
        // (Micrometer recorders, AiEventForwardingListener, audit appenders)
        // get a uniform per-call event surface — same posture as Built-in's
        // OpenAiCompatibleClient. Start fires before subscribe; End fires on
        // doOnComplete with the latest captured usage + wall-clock duration;
        // Error fires on doOnError. The captured-usage AtomicReference holds
        // the last TokenUsage seen during streaming because Spring AI emits
        // usage on the final ChatResponse, not as a separate event.
        var messageCount = context.history().size()
                + (context.systemPrompt() != null && !context.systemPrompt().isEmpty() ? 1 : 0)
                + 1; // user message
        var toolCount = context.tools().size();
        var lastUsage =
                new java.util.concurrent.atomic.AtomicReference<org.atmosphere.ai.TokenUsage>();
        var modelScope = org.atmosphere.ai.ModelCallScope.open(
                context.listeners(),
                context.model() != null ? context.model() : name(),
                messageCount, toolCount);

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
                        var tokenUsage = org.atmosphere.ai.TokenUsage.fromCounts(
                                u.getPromptTokens() != null
                                        ? u.getPromptTokens().longValue() : null,
                                u.getCompletionTokens() != null
                                        ? u.getCompletionTokens().longValue() : null,
                                null,
                                u.getTotalTokens() != null
                                        ? u.getTotalTokens().longValue() : null);
                        if (tokenUsage.hasCounts()) {
                            session.usage(tokenUsage);
                            lastUsage.set(tokenUsage);
                        }
                    }
                })
                .doOnComplete(() -> {
                    modelScope.complete(lastUsage.get());
                    session.complete();
                    completion.complete(null);
                })
                .doOnCancel(() -> completion.complete(null))
                .subscribe(
                        ignored -> { },
                        error -> {
                            // Terminal error path (Correctness Invariant #2). The
                            // error handling lives in the subscriber's onError
                            // consumer — not a doOnError side-effect — so that an
                            // error raised in the upstream publisher (e.g. the
                            // OpenAI SDK rejecting a provider's tool-call streaming
                            // delta) reaches session.error and resolves the
                            // completion future, instead of slipping past a
                            // handler-less subscribe() into Reactor's default
                            // onErrorDropped where it is silently swallowed and the
                            // session is left hanging.
                            modelScope.fail(error);
                            logger.error("Spring AI streaming error: {}", error.getMessage());
                            if (!session.isClosed()) {
                                session.error(error);
                            }
                            completion.complete(null);
                        });

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
                // NATIVE_STRUCTURED_OUTPUT is honest on the OpenAI-backed path:
                // doExecuteWithHandle threads the generated JSON Schema into
                // OpenAiChatOptions.outputSchema(...), which Spring AI 2.0 maps to
                // a strict json_schema response_format so OpenAI enforces the
                // schema at the provider level. Non-OpenAI ChatModels ignore the
                // OpenAI-specific option (schema harmlessly dropped, prompt
                // injection still applies); a provider rejection trips the
                // NativeStructuredOutputMode.AUTO graceful fall-back.
                AiCapability.NATIVE_STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT,
                // TOOL_APPROVAL is honest: SpringAiToolBridge.AtmosphereToolCallback
                // routes tool invocation through
                // ToolExecutionHelper.executeWithApproval on every call, so
                // @RequiresApproval gates fire uniformly across all runtimes.
                AiCapability.TOOL_APPROVAL,
                // VISION / AUDIO / MULTI_MODAL are honest: the doExecuteWithHandle
                // message assembler translates Content.Image and Content.Audio
                // parts on the context into Spring AI Media attached to the
                // user message. Underlying model support depends on the
                // configured ChatClient — admins who point Atmosphere at a
                // text-only model will get a provider-level error at dispatch,
                // not a silent drop.
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL,
                // PROMPT_CACHING is honest on the OpenAI path: doExecuteWithHandle
                // reads context.metadata()'s CacheHint and rides it through
                // OpenAiChatOptions.extraBody(Map.of("prompt_cache_key", ...))
                // on the prompt spec. (Spring AI 2.0 M5+ replaced the typed
                // promptCacheKey(String) builder method with the generic
                // extraBody escape hatch.) Non-OpenAI providers ignore the
                // OpenAI-specific extension field.
                AiCapability.PROMPT_CACHING,
                // TOKEN_USAGE: doExecuteWithHandle emits a typed TokenUsage
                // record via session.usage() when the Spring AI response
                // exposes non-null usage metadata — see lines 196-203.
                // CONVERSATION_MEMORY: AbstractAgentRuntime.assembleMessages
                // threads context.history() into the Prompt, so the
                // pipeline-managed history is honored on every request.
                AiCapability.TOKEN_USAGE,
                AiCapability.CONVERSATION_MEMORY,
                // PER_REQUEST_RETRY: honored via AbstractAgentRuntime's
                // outer retry wrapper (executeWithOuterRetry). Pre-stream
                // transient failures are retried up to the policy's
                // maxRetries budget on top of Spring AI's own retry layer,
                // delivering an "at least N retries" guarantee.
                AiCapability.PER_REQUEST_RETRY,
                // BUDGET_ENFORCEMENT: framework-level circuit breaker via the
                // AiPipeline BudgetCapturingSession decorator — honest because
                // doExecuteWithHandle pushes typed TokenUsage through
                // session.usage() on every Spring AI response, the signal
                // BudgetCapturingSession taps for token / step abort.
                AiCapability.BUDGET_ENFORCEMENT,
                // CONFIDENCE_SCORES: framework-level — AiPipeline's
                // ConfidenceCapturingSession parses the model-reported
                // confidence field on stream completion. Honest because
                // Spring AI honors SYSTEM_PROMPT and the reactive flux
                // pushes response text through session.send on every chunk.
                AiCapability.CONFIDENCE_SCORES,
                // PASSIVATION: AgentPassivation snapshots context.history()
                // into a CheckpointStore. Honest because Spring AI's
                // assembleMessages threads history into the Prompt on every
                // dispatch — a resumed call observes the same conversation.
                AiCapability.PASSIVATION,
                // CANCELLATION: doExecuteWithHandle wraps the Reactor
                // subscription's Disposable in the ExecutionHandle; cancel()
                // disposes it, propagating an upstream cancel to the streaming
                // chat completion and settling whenDone().
                AiCapability.CANCELLATION
        );
    }

    /**
     * Apply the framework-level {@link org.atmosphere.ai.GenerationParams} to a
     * Spring AI {@link ChatOptions.Builder} (works for both the generic builder
     * and the OpenAI-specific subclass, which both expose the generic
     * {@code temperature}/{@code maxTokens}/{@code topP}/{@code stopSequences}
     * setters). Only set components are forwarded; an unset component leaves the
     * options untouched so the wire stays byte-identical to today.
     *
     * @param builder    the options builder to mutate in place
     * @param generation the resolved overrides; {@link org.atmosphere.ai.GenerationParams#defaults()} is a no-op
     */
    private static void applyGeneration(ChatOptions.Builder<?> builder,
                                        org.atmosphere.ai.GenerationParams generation) {
        if (generation == null || !generation.hasAny()) {
            return;
        }
        if (generation.temperature() != null) {
            builder.temperature(generation.temperature());
        }
        if (generation.maxTokens() != null) {
            builder.maxTokens(generation.maxTokens());
        }
        if (generation.topP() != null) {
            builder.topP(generation.topP());
        }
        if (generation.stop() != null && !generation.stop().isEmpty()) {
            builder.stopSequences(generation.stop());
        }
    }

    private static Message toSpringMessage(ChatMessage msg) {
        return switch (msg.role()) {
            case "system" -> new SystemMessage(msg.content());
            case "assistant" -> new AssistantMessage(msg.content());
            default -> new UserMessage(msg.content());
        };
    }
}
