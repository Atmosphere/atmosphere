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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfidenceElicitation;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;

import java.util.Set;

/**
 * Default fallback {@link org.atmosphere.ai.AgentRuntime} that uses Atmosphere's
 * built-in OpenAI-compatible HTTP client. Priority 0 — always available, used
 * when no framework-specific runtime is on the classpath.
 */
public class BuiltInAgentRuntime extends AbstractAgentRuntime<LlmClient> {

    /**
     * Short-TTL cache for {@link #models()}. Best-effort: a failed or empty
     * live enumeration falls through to the configured-model fallback and is
     * not negatively cached.
     */
    private final CachedModelList modelCache = new CachedModelList();

    /**
     * Model captured at {@link #configure(AiConfig.LlmSettings)} time, used as
     * the {@link #models()} fallback when a live enumeration fails and the
     * process-wide {@link AiConfig} static is not installed. Null until
     * configured.
     */
    private volatile String configuredModel;

    /**
     * Built-in threads {@link AgentExecutionContext#retryPolicy()} into
     * {@link OpenAiCompatibleClient}'s {@code sendWithRetry} loop at the
     * HTTP layer — the native retry surface. Opting out of the base
     * class's outer retry wrapper prevents double-retries.
     */
    @Override
    protected boolean ownsPerRequestRetry() {
        return true;
    }

    @Override
    public String name() {
        return "built-in";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    protected String nativeClientClassName() {
        return "org.atmosphere.ai.llm.LlmClient";
    }

    @Override
    protected String clientDescription() {
        return "LlmClient";
    }

    @Override
    protected LlmClient createNativeClient(AiConfig.LlmSettings settings) {
        return settings != null ? settings.client() : null;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (settings != null && settings.model() != null && !settings.model().isBlank()) {
            // Remember the model this runtime was configured with so models()
            // can report it even when the process-wide AiConfig static was
            // never installed (direct/embedded wiring). Dispatch still resolves
            // per-request through effectiveModel(...); this is the discovery
            // fallback only.
            configuredModel = settings.model();
        }
        if (getNativeClient() == null && settings != null) {
            setNativeClient(settings.client());
        }
    }

    @Override
    protected void doExecute(LlmClient client,
                             AgentExecutionContext context, StreamingSession session) {
        admitThroughGateway(context);
        client.streamChatCompletion(buildRequest(context), session);
    }

    /**
     * D-6 Built-in hard-cancel: returns an {@link org.atmosphere.ai.ExecutionHandle}
     * whose {@code cancel()} closes the in-flight SSE {@link java.io.InputStream}
     * from another thread, interrupting the blocked {@code BufferedReader.readLine()}
     * immediately instead of waiting for the HTTP timeout or the next SSE frame.
     * The cancelled flag is kept as a secondary safeguard for the gap between
     * tool rounds when no stream is open.
     */
    @Override
    protected org.atmosphere.ai.ExecutionHandle doExecuteWithHandle(
            LlmClient client, AgentExecutionContext context, StreamingSession session) {
        // Gateway admission MUST happen on every dispatch path so rate
        // limits and credential-choke-point policies see the handle-based
        // flow too. Prior to this the cancel-capable path bypassed the
        // gateway — parity regression covered by RuntimeCapabilityParityTest.
        admitThroughGateway(context);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var inFlightStream = new java.util.concurrent.atomic.AtomicReference<java.io.Closeable>();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        java.util.function.Consumer<java.io.Closeable> streamSink = inFlightStream::set;
        Thread.startVirtualThread(() -> {
            try {
                client.streamChatCompletion(buildRequest(context), session, cancelled, streamSink);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return new org.atmosphere.ai.ExecutionHandle() {
            @Override public void cancel() {
                cancelled.set(true);
                var stream = inFlightStream.getAndSet(null);
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (java.io.IOException ignored) {
                        // Already closed or failed to close — the cancel flag
                        // is the fallback and the read loop will exit at the
                        // next boundary.
                    }
                }
            }
            @Override public boolean isDone() { return done.isDone(); }
            @Override public java.util.concurrent.CompletableFuture<Void> whenDone() { return done; }
        };
    }

    private ChatCompletionRequest buildRequest(AgentExecutionContext context) {
        // Resolve the effective model: an explicit context.model() wins, else
        // the framework-configured AiConfig model. Without this fallback a
        // caller that builds a context with a null model (e.g. long-term-memory
        // fact extraction) would send model=null and get an empty/failed
        // completion, silently storing no memory.
        var builder = ChatCompletionRequest.builder(effectiveModel(context, null));
        for (var msg : assembleMessages(context)) {
            builder.message(msg);
        }
        if (context.responseType() != null) {
            // json_object is the baseline (and the graceful fall-back when native
            // is off or a provider rejects the schema). When the pipeline opts
            // into provider-native structured output, upgrade to the strict
            // json_schema response_format so OpenAI enforces the schema itself.
            builder.jsonMode(true);
            if (org.atmosphere.ai.NativeStructuredOutput.shouldApply(context)) {
                var schema = org.atmosphere.ai.NativeStructuredOutput.schema(context);
                if (schema != null) {
                    builder.jsonSchema(schema);
                }
            }
        }
        if (!context.tools().isEmpty()) {
            builder.tools(context.tools());
        }
        if (context.conversationId() != null) {
            builder.conversationId(context.conversationId());
        }
        if (context.approvalStrategy() != null) {
            builder.approvalStrategy(context.approvalStrategy());
        }
        if (!context.parts().isEmpty()) {
            // Multi-modal parts ride the request as a separate field so
            // OpenAiCompatibleClient.buildRequestBody can emit them as the
            // OpenAI multi-content array on the last user message without
            // disturbing the plain-text fast path.
            builder.parts(context.parts());
        }
        if (!context.listeners().isEmpty()) {
            // Per-tool lifecycle events fire from the SSE tool loop inside
            // OpenAiCompatibleClient, which only sees the ChatCompletionRequest.
            // Thread the context's listeners through the request so the loop
            // can call AgentLifecycleListener.fireToolCall / fireToolResult
            // on every round.
            builder.listeners(context.listeners());
        }
        // Prompt-caching: context metadata may carry a CacheHint which the
        // OpenAiCompatibleClient translates into a {@code prompt_cache_key}
        // field on the outgoing JSON. Falls back to the session id when the
        // caller did not supply an explicit key — OpenAI's own recommendation
        // for multi-turn reuse.
        var hint = CacheHint.from(context);
        if (hint.enabled()) {
            var resolvedKey = hint.resolvedKey(context);
            if (resolvedKey.isPresent()) {
                builder.cacheHint(new CacheHint(hint.policy(), resolvedKey, hint.ttl()));
            }
        }
        // Per-request RetryPolicy override flows from context into the
        // request so OpenAiCompatibleClient.sendWithRetry uses it instead
        // of the client's instance-level default. The sentinel check is
        // formalised on RetryPolicy.isInheritSentinel() rather than open-
        // coding a reference comparison here — see the Javadoc on
        // RetryPolicy.DEFAULT for the full contract.
        var ctxPolicy = context.retryPolicy();
        if (ctxPolicy != null && !ctxPolicy.isInheritSentinel()) {
            builder.retryPolicy(ctxPolicy);
        }
        if (context.approvalPolicy() != null) {
            builder.approvalPolicy(context.approvalPolicy());
        }
        // Per-request tool-loop policy: caller attaches via
        // ToolLoopPolicies.attach(context, ToolLoopPolicy.strict(3)) or via
        // an interceptor that stamps METADATA_KEY. Null-from means the caller
        // did not opt in — leave the builder default (ToolLoopPolicy.DEFAULT)
        // which preserves the historical 5-iteration cap with
        // complete-without-tools overflow behavior.
        var loopPolicy = ToolLoopPolicies.from(context);
        if (loopPolicy != null) {
            builder.toolLoopPolicy(loopPolicy);
        }
        // Native-logprobs confidence: when the pipeline installed a confidence
        // elicitation for this request (AiPipeline seeds its default into
        // metadata and the caller's own wins), ask the provider for token
        // logprobs so OpenAiCompatibleClient can emit an AiConfidence with
        // source LOGPROBS_NATIVE — the highest-quality signal — instead of
        // relying solely on the model-reported-field parse. The client gates
        // the actual wire field on LogprobsMode + the shared endpoint
        // allow-list, and the ConfidenceCapturingSession decorator observes
        // the explicit emission and skips its own, so exactly one confidence
        // event fires per response. With no elicitation in scope the flag
        // stays false and the request body is byte-identical to before.
        if (AiConfidenceElicitation.from(context) != null) {
            builder.logprobs(true);
        }
        return builder.build();
    }

    /**
     * Live model enumeration via the configured OpenAI-compatible endpoint's
     * {@code GET {baseUrl}/models}, cached for a short TTL and always falling
     * back to the framework-configured model on any error, timeout, or empty
     * result — enumeration failure can never break dispatch. Backs
     * {@link AiCapability#MODEL_ENUMERATION}.
     */
    @Override
    public java.util.List<String> models() {
        var client = getNativeClient();
        if (client == null) {
            return fallbackModels();
        }
        return modelCache.get("Built-in", client::listModels, this::fallbackModels);
    }

    /**
     * Configured-model fallback for {@link #models()}: the framework-resolved
     * {@link AiConfig} model when the static is installed, otherwise the model
     * captured at {@link #configure(AiConfig.LlmSettings)} time. Empty only
     * when this runtime has no configured model at all.
     */
    private java.util.List<String> fallbackModels() {
        var configured = super.models();
        if (!configured.isEmpty()) {
            return configured;
        }
        var local = configuredModel;
        return local != null ? java.util.List.of(local) : java.util.List.of();
    }

    @Override
    public Set<AiCapability> capabilities() {
        // STRUCTURED_OUTPUT is honored two ways: (1) the AiPipeline wraps the session
        // in StructuredOutputCapturingSession and augments the system prompt with schema
        // instructions (same path every other runtime relies on), and (2) doExecute
        // additionally enables native OpenAI jsonMode on the underlying client when
        // responseType is present — see lines 72-74 above. Declaring it keeps the
        // SPI contract honest (Correctness Invariant #5 — Runtime Truth).
        //
        // TOOL_APPROVAL is honest because every tool invocation routes through
        // ToolExecutionHelper.executeWithApproval — the OpenAiCompatibleClient
        // tool-call loop at OpenAiCompatibleClient.java:~323 calls the shared
        // helper on every tool call, so @RequiresApproval gates fire uniformly.
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                // NATIVE_STRUCTURED_OUTPUT is honest: buildRequest threads the
                // generated JSON Schema into ChatCompletionRequest.jsonSchema when
                // the pipeline opts in, and OpenAiCompatibleClient.buildRequestBody
                // emits it as response_format:{type:"json_schema",strict:true} so
                // OpenAI enforces the schema at the provider level (not just via
                // the prompt). Falls back to json_object on rejection (AUTO mode).
                AiCapability.NATIVE_STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.TOOL_APPROVAL,
                // VISION / AUDIO / MULTI_MODAL are honest: buildRequest
                // threads Content.Image and Content.Audio parts through
                // ChatCompletionRequest.parts, and
                // OpenAiCompatibleClient.buildRequestBody translates them
                // into the OpenAI multi-content array format on the last
                // user message:
                //   images → {"type":"image_url","image_url":{"url":"data:<mime>;base64,..."}}
                //   audio  → {"type":"input_audio","input_audio":{"data":"<b64>","format":"mp3"}}
                // Audio is supported on gpt-4o-audio-preview and other
                // audio-capable chat-completion models. Pointing Atmosphere
                // at a text-only model produces a provider-level error at
                // dispatch (not a silent drop), matching the posture every
                // other multi-modal-capable runtime takes.
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL,
                // PROMPT_CACHING is honest: a CacheHint in context.metadata()
                // under key {@code ai.cache.hint} becomes a
                // {@code prompt_cache_key} JSON field in the outgoing OpenAI
                // chat-completions request body. See buildRequest() + the
                // serializer in OpenAiCompatibleClient.buildRequestBody.
                AiCapability.PROMPT_CACHING,
                // PER_REQUEST_RETRY: buildRequest threads context.retryPolicy()
                // into ChatCompletionRequest, which OpenAiCompatibleClient's
                // sendWithRetry loop uses as the per-request override. Built-in
                // is the only runtime that honors this today — framework
                // runtimes inherit their own retry layers (Correctness
                // Invariant #7, Mode Parity).
                AiCapability.PER_REQUEST_RETRY,
                // TOKEN_USAGE: OpenAiCompatibleClient emits a typed TokenUsage
                // record (including cachedInput) via session.usage() on every
                // completed request — see OpenAiCompatibleClient.java:576, 964.
                // CONVERSATION_MEMORY: AbstractAgentRuntime.assembleMessages
                // threads context.history() into every outbound request, so
                // the pipeline-managed history is honored even though this
                // runtime does not persist it framework-side.
                AiCapability.TOKEN_USAGE,
                AiCapability.CONVERSATION_MEMORY,
                // TOOL_CALL_DELTA: OpenAiCompatibleClient's chat-completions
                // tool-call loop and responses-API streaming loop both call
                // session.toolCallDelta(acc.id(), chunk) on every
                // delta.tool_calls[].function.arguments fragment (see
                // OpenAiCompatibleClient.java lines ~530 and ~892). The six
                // framework bridges cannot emit deltas without bypassing
                // their high-level streaming APIs — Correctness Invariant #5
                // (Runtime Truth): only the runtime that actually forwards
                // chunks to session.toolCallDelta declares the capability.
                AiCapability.TOOL_CALL_DELTA,
                // BUDGET_ENFORCEMENT: declared by every runtime that streams
                // through AiPipeline. The budget decorator lives at the pipeline
                // layer above the runtime, so token/step/wall-clock breaches
                // are caught regardless of which runtime is dispatching. The
                // capability flag tells callers "yes, this runtime cooperates
                // with the framework-level circuit breaker"; honest because
                // doExecute pushes through StreamingSession.usage() which is
                // exactly the signal BudgetCapturingSession taps.
                AiCapability.BUDGET_ENFORCEMENT,
                // CONFIDENCE_SCORES: two paths, both live. (1) Framework
                // level — when an AiConfidenceElicitation is configured,
                // AiPipeline appends the cue to the system prompt and the
                // ConfidenceCapturingSession decorator parses the model's
                // emitted confidence field on stream completion. (2) Native
                // logprobs — buildRequest sets ChatCompletionRequest.logprobs
                // whenever an elicitation is in scope, OpenAiCompatibleClient
                // emits `logprobs: true` on the chat-completions wire (gated
                // by LogprobsMode + the shared endpoint allow-list), captures
                // choices[].logprobs.content, and fires
                // AiConfidence.fromLogprobs(...) — source LOGPROBS_NATIVE —
                // before the terminal frame. The decorator sees the explicit
                // emission and suppresses its own parse, so exactly one
                // confidence event fires per response and the richer signal
                // wins when the provider supplies it.
                AiCapability.CONFIDENCE_SCORES,
                // MODEL_ENUMERATION: models() calls the configured
                // OpenAI-compatible endpoint's GET {baseUrl}/models through
                // OpenAiCompatibleClient.listModels(), cached for a short TTL
                // (CachedModelList) and always falling back to the
                // AiConfig-configured model on any error/timeout/empty result.
                // Honest because the list reflects runtime-resolved provider
                // state, not configuration intent (Correctness Invariant #5).
                AiCapability.MODEL_ENUMERATION,
                // PASSIVATION: AgentPassivation (modules/checkpoint) snapshots
                // context.history() into a CheckpointStore and rehydrates on
                // resume. Honest because Built-in's assembleMessages threads
                // history into every outbound request, so a resumed call
                // observes the same conversation the paused call was seeing.
                AiCapability.PASSIVATION,
                // CANCELLATION: doExecuteWithHandle returns a live handle whose
                // cancel() closes the in-flight SSE InputStream, aborting the
                // OpenAI-compatible streaming request and settling whenDone().
                AiCapability.CANCELLATION);
    }
}
