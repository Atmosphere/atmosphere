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
                // VISION / MULTI_MODAL are honest: buildRequest threads
                // Content.Image parts through ChatCompletionRequest.parts,
                // and OpenAiCompatibleClient.buildRequestBody translates them
                // into the OpenAI multi-content array format
                // ({"type":"image_url","image_url":{"url":"data:<mime>;base64,..."}})
                // on the last user message. AUDIO is excluded — the OpenAI
                // chat completions API does not accept audio input on the
                // text-model surface (Whisper is a separate endpoint).
                AiCapability.VISION,
                AiCapability.MULTI_MODAL);
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
