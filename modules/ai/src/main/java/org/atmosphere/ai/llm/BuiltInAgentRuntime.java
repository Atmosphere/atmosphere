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
     * D-6 Built-in native cancel: wires a soft-cancel {@link java.util.concurrent.atomic.AtomicBoolean}
     * into {@link LlmClient#streamChatCompletion(ChatCompletionRequest, org.atmosphere.ai.StreamingSession, java.util.concurrent.atomic.AtomicBoolean)}
     * and returns an {@link org.atmosphere.ai.ExecutionHandle} whose
     * {@code cancel()} flips the flag. The SSE read loop in
     * {@link OpenAiCompatibleClient#streamChatCompletion(ChatCompletionRequest, org.atmosphere.ai.StreamingSession, java.util.concurrent.atomic.AtomicBoolean)}
     * polls the flag on every line and exits cleanly at the next SSE boundary.
     * Cancellation is soft — the in-flight HTTP request is not aborted — but
     * every subsequent SSE line is dropped and the session sees no additional
     * tokens.
     */
    @Override
    protected org.atmosphere.ai.ExecutionHandle doExecuteWithHandle(
            LlmClient client, AgentExecutionContext context, StreamingSession session) {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                client.streamChatCompletion(buildRequest(context), session, cancelled);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return new org.atmosphere.ai.ExecutionHandle() {
            @Override public void cancel() { cancelled.set(true); }
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
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.SYSTEM_PROMPT);
    }
}
