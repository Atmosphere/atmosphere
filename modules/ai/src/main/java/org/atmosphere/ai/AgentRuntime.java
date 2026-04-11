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
package org.atmosphere.ai;

import java.time.Duration;
import java.util.Set;

/**
 * SPI for AI agent execution. Replaces {@code AiSupport} with a richer contract
 * that dispatches the entire agent loop — tool calling, memory, RAG, retries —
 * to the AI framework on the classpath.
 *
 * <p>Drop a framework adapter JAR on the classpath and Atmosphere auto-detects
 * it via {@link java.util.ServiceLoader}. When multiple implementations are
 * available, the one with the highest {@link #priority()} that reports
 * {@link #isAvailable()} wins.</p>
 *
 * <p>This is the Servlet model for AI agents: write your {@code @Agent} once,
 * run it on LangChain4j, Google ADK, Spring AI, or standalone — determined
 * by classpath.</p>
 *
 * @see AgentExecutionContext
 * @see AgentRuntimeResolver
 */
public interface AgentRuntime {

    /**
     * Human-readable name (e.g., "langchain4j", "spring-ai", "google-adk", "built-in").
     */
    String name();

    /**
     * Whether this runtime's required dependencies are on the classpath.
     */
    boolean isAvailable();

    /**
     * Priority for auto-detection. Higher values win.
     * The built-in runtime uses priority {@code 0}.
     */
    int priority();

    /**
     * Configure this runtime with LLM settings. Called once after resolution.
     *
     * @param settings the resolved LLM settings
     */
    void configure(AiConfig.LlmSettings settings);

    /**
     * Capabilities supported by this runtime. Used for smart model routing,
     * tool calling negotiation, and feature discovery.
     *
     * @return the set of capabilities this runtime supports
     */
    default Set<AiCapability> capabilities() {
        return Set.of(AiCapability.TEXT_STREAMING);
    }

    /**
     * Execute the full agent loop for a message. The runtime owns tool calling,
     * memory management, RAG augmentation, retries, and streaming. Results are
     * pushed through the session.
     *
     * <p>Guardrails and interceptors are <em>not</em> the runtime's
     * responsibility — they wrap the runtime call externally in the Atmosphere
     * pipeline.</p>
     *
     * @param context the execution context (message, tools, memory, RAG providers, history)
     * @param session the streaming session to push results through
     */
    void execute(AgentExecutionContext context, StreamingSession session);

    /**
     * Execute a prompt and return a cooperative cancellation handle. Phase 2
     * of the unified {@code @Agent} API closes Correctness Invariant #2
     * (Terminal Path Completeness) by letting callers abort in-flight
     * completions via a uniform API that wraps each backend's native cancel
     * primitive.
     *
     * <p>The default implementation delegates to
     * {@link #execute(AgentExecutionContext, StreamingSession)} and returns
     * {@link ExecutionHandle#completed()} — legacy runtimes keep working
     * unchanged. Runtimes that support cancellation override this method to
     * return a handle whose {@link ExecutionHandle#cancel()} fires the
     * native primitive (Reactor {@code Disposable.dispose()}, Koog
     * {@code Job.cancel()}, ADK {@code Runner.close()}, Built-in HttpClient
     * request cancel, etc.).</p>
     *
     * <p>Callers that need cancellation call this method and keep the returned
     * handle; callers that do not care keep using the void overload.</p>
     *
     * @param context the execution context (message, tools, memory, RAG, history)
     * @param session the streaming session sink
     * @return a handle the caller can use to cancel or await termination
     */
    default ExecutionHandle executeWithHandle(
            AgentExecutionContext context, StreamingSession session) {
        execute(context, session);
        return ExecutionHandle.completed();
    }

    /**
     * Phase 11 of the unified {@code @Agent} API: enumerate the models this
     * runtime can serve. Used by the admin / discovery surface to display
     * runtime-resolved model lists instead of advertising configuration
     * intent (Correctness Invariant #5 — Runtime Truth).
     *
     * <p>The default implementation returns an empty list — runtimes that can
     * answer without an extra network call should override.</p>
     *
     * @return immutable list of model identifiers; never null
     */
    default java.util.List<String> models() {
        return java.util.List.of();
    }

    /**
     * Typed synchronous convenience (D-1 follow-up to Phase 1). Runs the
     * runtime in a {@link CollectingSession} that captures any
     * {@link StreamingSession#usage} events the runtime emits, then returns a
     * typed {@link AgentExecutionResult} with text + usage + duration.
     *
     * <p>Implemented as a default so every runtime inherits it without
     * breaking the existing {@link #generate} String API.</p>
     */
    default AgentExecutionResult generateResult(AgentExecutionContext context) {
        return generateResult(context, Duration.ofSeconds(30));
    }

    /** Typed synchronous convenience with a custom timeout. */
    default AgentExecutionResult generateResult(AgentExecutionContext context, Duration timeout) {
        var collector = new CollectingSession();
        var captured = new java.util.concurrent.atomic.AtomicReference<TokenUsage>();
        // Wrap the collector so session.usage() events from the runtime are
        // captured in addition to the text. The wrapper forwards everything
        // else to the underlying CollectingSession.
        StreamingSession usageCapturing = new StreamingSession() {
            @Override public String sessionId() { return collector.sessionId(); }
            @Override public void send(String text) { collector.send(text); }
            @Override public void sendMetadata(String key, Object value) { collector.sendMetadata(key, value); }
            @Override public void progress(String message) { collector.progress(message); }
            @Override public void complete() { collector.complete(); }
            @Override public void complete(String summary) { collector.complete(summary); }
            @Override public void error(Throwable t) { collector.error(t); }
            @Override public boolean isClosed() { return collector.isClosed(); }
            @Override public void emit(AiEvent event) { collector.emit(event); }
            @Override public void sendContent(Content content) { collector.sendContent(content); }
            @Override public void usage(TokenUsage usage) {
                collector.usage(usage);
                if (usage != null && usage.hasCounts()) {
                    captured.set(usage);
                }
            }
        };
        var start = java.time.Instant.now();
        execute(context, usageCapturing);
        if (!collector.isClosed()) {
            collector.await(timeout);
        }
        if (!collector.isClosed()) {
            collector.complete();
        }
        var elapsed = Duration.between(start, java.time.Instant.now());
        return new AgentExecutionResult(
                collector.text(),
                java.util.Optional.ofNullable(captured.get()),
                elapsed,
                java.util.Optional.ofNullable(context.model()));
    }

    /**
     * Synchronous convenience: execute a prompt and return the full response
     * as a string. Uses a {@link CollectingSession} internally with a 30-second
     * default timeout.
     *
     * <p>Framework runtimes can override this with native synchronous calls
     * (e.g., LangChain4j's {@code ChatLanguageModel.generate()}).</p>
     *
     * @param context the execution context
     * @return the collected response text
     */
    default String generate(AgentExecutionContext context) {
        return generate(context, Duration.ofSeconds(30));
    }

    /**
     * Synchronous convenience with a custom timeout.
     *
     * @param context the execution context
     * @param timeout maximum wait time
     * @return the collected response text, or empty string if timed out
     */
    default String generate(AgentExecutionContext context, Duration timeout) {
        var collector = new CollectingSession();
        execute(context, collector);
        // Wait for the runtime to signal completion (async runtimes stream on
        // background threads). If the runtime doesn't call complete() within
        // the timeout, force-close as a safety net — but NEVER before awaiting.
        if (!collector.isClosed()) {
            collector.await(timeout);
        }
        if (!collector.isClosed()) {
            collector.complete();
        }
        return collector.text();
    }
}
