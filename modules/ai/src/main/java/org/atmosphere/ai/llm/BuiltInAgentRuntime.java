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
     * Route the dispatch through the process-wide {@code AiGatewayHolder}
     * so per-user rate limits and credential lookups apply uniformly
     * regardless of which runtime the caller picked. The permissive
     * default gateway returns accepted unconditionally; applications that
     * install a real gateway at startup get real enforcement.
     *
     * <p>A rejected admission surfaces as {@link IllegalStateException}
     * with the reason attached — callers MUST honor rejection per
     * Correctness Invariant #3 (never ignore backpressure).</p>
     */
    private static void admitThroughGateway(AgentExecutionContext context) {
        var gateway = org.atmosphere.ai.gateway.AiGatewayHolder.get();
        var userId = context.userId() != null ? context.userId() : "anonymous";
        var decision = gateway.admit(userId, "built-in",
                context.model() != null ? context.model() : "default");
        if (!decision.accepted()) {
            throw new IllegalStateException(
                    "AiGateway rejected call for user " + userId + ": " + decision.reason());
        }
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
        var builder = ChatCompletionRequest.builder(context.model());
        for (var msg : assembleMessages(context)) {
            builder.message(msg);
        }
        if (context.responseType() != null) {
            builder.jsonMode(true);
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
        return builder.build();
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
                AiCapability.TOOL_CALL_DELTA);
    }

    @Override
    public java.util.List<String> models() {
        var settings = AiConfig.get();
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            return java.util.List.of();
        }
        return java.util.List.of(settings.model());
    }
}
