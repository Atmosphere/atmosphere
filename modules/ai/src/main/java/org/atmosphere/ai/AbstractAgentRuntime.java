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

import org.atmosphere.ai.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base class for {@link AgentRuntime} implementations. Provides classpath-based
 * availability detection, lazy client initialization, and a template method
 * ({@link #doExecute}) for framework-specific execution.
 *
 * @param <C> the native client type (e.g. {@code ChatClient}, {@code StreamingChatModel})
 */
public abstract class AbstractAgentRuntime<C> implements AgentRuntime {

    private static final Logger lifecycleLogger = LoggerFactory.getLogger(AbstractAgentRuntime.class);

    private volatile C nativeClient;

    /** Returns the current native client instance, or {@code null} if not yet configured. */
    protected C getNativeClient() {
        return nativeClient;
    }

    /** Sets the native client instance. */
    protected void setNativeClient(C client) {
        this.nativeClient = client;
    }

    /**
     * Returns the fully-qualified class name used to check whether this
     * runtime's dependencies are on the classpath.
     */
    protected abstract String nativeClientClassName();

    /**
     * Creates the native client from the given LLM settings. Called by
     * {@link #configure(AiConfig.LlmSettings)} when no client has been set yet.
     *
     * @param settings the resolved LLM settings
     * @return the native client, or {@code null} if configuration is not possible
     */
    protected abstract C createNativeClient(AiConfig.LlmSettings settings);

    /**
     * Performs the actual agent execution after the native client has been resolved.
     *
     * @param client  the non-null native client
     * @param context the execution context
     * @param session the streaming session
     */
    protected abstract void doExecute(C client, AgentExecutionContext context,
                                      StreamingSession session);

    /**
     * Cancellation-aware variant of {@link #doExecute}. The default
     * implementation blocks on {@link #doExecute} and returns an
     * already-completed handle — runtimes with a native cancel primitive
     * (Reactor {@code Disposable}, Koog {@code Job}, ADK {@code Runner.close},
     * Built-in HttpClient request cancel) should override this to return a
     * handle whose {@link ExecutionHandle#cancel()} fires the native
     * primitive and whose {@link ExecutionHandle#whenDone()} completes when
     * the native pipeline terminates.
     *
     * <p>Phase 2 of the unified {@code @Agent} API: the default keeps legacy
     * runtimes wire-compatible, and subclasses opt in to real cancellation.</p>
     *
     * @param client  the non-null native client
     * @param context the execution context
     * @param session the streaming session
     * @return a handle the caller can use to cancel or await termination
     */
    protected ExecutionHandle doExecuteWithHandle(
            C client, AgentExecutionContext context, StreamingSession session) {
        doExecute(client, context, session);
        return ExecutionHandle.completed();
    }

    /**
     * Returns a human-readable description of the client type for error messages.
     */
    protected abstract String clientDescription();

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(nativeClientClassName());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (nativeClient != null) {
            return;
        }
        nativeClient = createNativeClient(settings);
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        var client = resolveClient();
        session.progress("Connecting to " + name() + "...");
        fireStart(context);
        try {
            executeWithOuterRetry(client, context, session);
            // If the bridge drained the stream but surfaced an error out-of-band
            // via session.error(...) (Spring AI reactive, ADK async callbacks,
            // LC4j onError, Koog error frames), honour that state instead of
            // reporting completion to listeners.
            if (session.hasErrored()) {
                fireError(context, new java.util.concurrent.CancellationException(
                        "stream reported error via session.error(...)"));
            } else {
                fireCompletion(context);
            }
        } catch (RuntimeException e) {
            fireError(context, e);
            throw e;
        }
    }

    @Override
    public ExecutionHandle executeWithHandle(
            AgentExecutionContext context, StreamingSession session) {
        var client = resolveClient();
        session.progress("Connecting to " + name() + "...");
        fireStart(context);
        try {
            var handle = doExecuteWithHandle(client, context, session);
            handle.whenDone().whenComplete((ok, err) -> {
                // Three outcomes map to two listener events:
                //   1. future completed exceptionally  → fireError(err)
                //   2. future completed normally, session errored out of band → fireError
                //   3. future completed normally, session clean → fireCompletion
                // Without the session.hasErrored() check, case 2 was silently
                // misreported as onCompletion — Spring AI and ADK reactive
                // bridges can drain a faulted stream while still resolving
                // whenDone() with a null value.
                if (err != null) {
                    fireError(context, err);
                } else if (session.hasErrored()) {
                    fireError(context, new java.util.concurrent.CancellationException(
                            "stream reported error via session.error(...)"));
                } else {
                    fireCompletion(context);
                }
            });
            return handle;
        } catch (RuntimeException e) {
            fireError(context, e);
            throw e;
        }
    }

    /**
     * Resolve (or lazily create) the native client. Factored out so both
     * {@link #execute} and {@link #executeWithHandle} share identical setup
     * semantics and error messages.
     */
    private C resolveClient() {
        var client = nativeClient;
        if (client == null) {
            var settings = AiConfig.get();
            if (settings == null) {
                settings = AiConfig.fromEnvironment();
            }
            configure(settings);
            client = nativeClient;
        }
        if (client == null) {
            throw new IllegalStateException(
                    name() + ": " + clientDescription() + " not configured. "
                            + configurationHint());
        }
        return client;
    }

    /**
     * Returns a hint message for the {@link IllegalStateException} thrown when
     * the native client is not configured.
     */
    protected String configurationHint() {
        return "Check your classpath and configuration.";
    }

    /**
     * Phase 3 helper: fire {@link AgentLifecycleListener#onStart} on every
     * listener attached to the context. Runtime bridges call this once at the
     * beginning of their execution path. Exceptions thrown by listeners are
     * caught and logged at TRACE so one broken listener cannot abort the
     * pipeline.
     */
    protected static void fireStart(AgentExecutionContext context) {
        for (var listener : context.listeners()) {
            try {
                listener.onStart(context);
            } catch (Exception e) {
                lifecycleLogger.trace("AgentLifecycleListener.onStart failed: {}", e.getMessage(), e);
            }
        }
    }

    /** Phase 3 helper: fire {@link AgentLifecycleListener#onToolCall}. */
    protected static void fireToolCall(AgentExecutionContext context, String toolName,
                                        Map<String, Object> arguments) {
        for (var listener : context.listeners()) {
            try {
                listener.onToolCall(toolName, arguments);
            } catch (Exception e) {
                lifecycleLogger.trace("AgentLifecycleListener.onToolCall failed: {}", e.getMessage(), e);
            }
        }
    }

    /** Phase 3 helper: fire {@link AgentLifecycleListener#onToolResult}. */
    protected static void fireToolResult(AgentExecutionContext context, String toolName,
                                          String resultPreview) {
        for (var listener : context.listeners()) {
            try {
                listener.onToolResult(toolName, resultPreview);
            } catch (Exception e) {
                lifecycleLogger.trace("AgentLifecycleListener.onToolResult failed: {}", e.getMessage(), e);
            }
        }
    }

    /** Phase 3 helper: fire {@link AgentLifecycleListener#onCompletion}. */
    protected static void fireCompletion(AgentExecutionContext context) {
        for (var listener : context.listeners()) {
            try {
                listener.onCompletion(context);
            } catch (Exception e) {
                lifecycleLogger.trace("AgentLifecycleListener.onCompletion failed: {}", e.getMessage(), e);
            }
        }
    }

    /** Phase 3 helper: fire {@link AgentLifecycleListener#onError}. */
    protected static void fireError(AgentExecutionContext context, Throwable error) {
        for (var listener : context.listeners()) {
            try {
                listener.onError(context, error);
            } catch (Exception e) {
                lifecycleLogger.trace("AgentLifecycleListener.onError failed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Subclass hook: return {@code true} when this runtime already honours
     * {@link AgentExecutionContext#retryPolicy()} at the native client layer
     * (Built-in's {@link org.atmosphere.ai.llm.OpenAiCompatibleClient}'s
     * {@code sendWithRetry} loop). When {@code true}, the base class skips
     * the outer retry wrapper in {@link #execute} so we do not double-retry.
     *
     * <p>Default: {@code false} — framework runtimes inherit the outer
     * wrapper, which retries on pre-stream transient failures.</p>
     */
    protected boolean ownsPerRequestRetry() {
        return false;
    }

    /**
     * Outer retry wrapper for framework runtimes that cannot hook
     * {@link AgentExecutionContext#retryPolicy()} into their native HTTP
     * client. When the caller supplies a non-default {@link RetryPolicy}
     * and this subclass does not {@link #ownsPerRequestRetry()}, the base
     * class retries {@link #doExecute} on pre-stream transient failures
     * until the policy's {@code maxRetries} budget is exhausted.
     *
     * <p><b>Safety semantics:</b> we only retry when the bridge threw a
     * {@link RuntimeException} BEFORE calling {@link StreamingSession#error(Throwable)}
     * — i.e., the session is still in a virgin state, the client has not
     * seen any terminal error frame, and retrying is safe. Once the
     * session reports an error out-of-band (reactive streams path, ADK
     * async callbacks, LC4j {@code onError}, Koog error frames), retry is
     * aborted and the exception propagates, because the caller has
     * already observed terminal state.</p>
     *
     * <p>This delivers an "at least N retries" guarantee on top of
     * whatever retries the framework's own HTTP client performs — strictly
     * more retries than the caller asked for, never fewer — which is the
     * correct direction for honouring a per-request override (Correctness
     * Invariant #7, Mode Parity).</p>
     */
    private void executeWithOuterRetry(C client, AgentExecutionContext context,
                                       StreamingSession session) {
        var policy = context.retryPolicy();
        if (policy == null || policy.isInheritSentinel() || policy.maxRetries() <= 0
                || ownsPerRequestRetry()) {
            doExecute(client, context, session);
            return;
        }
        int attempt = 0;
        while (true) {
            try {
                doExecute(client, context, session);
                return;
            } catch (RuntimeException e) {
                if (attempt >= policy.maxRetries() || session.hasErrored()) {
                    throw e;
                }
                var delay = policy.delayForAttempt(attempt);
                attempt++;
                lifecycleLogger.info(
                        "{} outer-retry attempt {}/{} after {}ms (cause: {})",
                        name(), attempt, policy.maxRetries(),
                        delay.toMillis(), e.getMessage());
                if (!delay.isZero() && !delay.isNegative()) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * Assemble the canonical message list from an execution context:
     * system prompt (if present) + conversation history + current user message.
     *
     * <p>Runtimes that need framework-specific message types can call this
     * method first and then translate each {@link ChatMessage} to their
     * native type.</p>
     *
     * @param context the execution context
     * @return an unmodifiable list of messages in conversation order
     */
    protected static List<ChatMessage> assembleMessages(AgentExecutionContext context) {
        var messages = new ArrayList<ChatMessage>();
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            messages.add(ChatMessage.system(context.systemPrompt()));
        }
        for (var h : context.history()) {
            messages.add(new ChatMessage(h.role(), h.content()));
        }
        messages.add(ChatMessage.user(context.message()));
        return List.copyOf(messages);
    }
}
