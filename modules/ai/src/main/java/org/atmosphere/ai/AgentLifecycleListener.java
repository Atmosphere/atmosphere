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

import java.util.List;
import java.util.Map;

/**
 * Server-side lifecycle observer for an {@link AgentRuntime} execution. Phase 3
 * of the unified {@code @Agent} API promotes the ad-hoc logging/metric hooks
 * scattered across runtime bridges into a single listener interface that
 * mirrors the pattern {@code AtmosphereResourceEventListener} uses in
 * {@code atmosphere-runtime}.
 *
 * <p>Listeners attach to an {@link AgentExecutionContext} and fire in FIFO
 * order around the runtime's native event stream (Spring AI {@code Advisor},
 * LC4j {@code ChatModelListener}, Koog {@code PromptExecutorInterceptor}, ADK
 * {@code BeforeAgentCallback}/{@code AfterAgentCallback}, Embabel
 * {@code AgentListener}). Methods are default no-ops so listeners only
 * override what they care about.</p>
 *
 * <p>Listeners run synchronously on the runtime's execution thread; throwing
 * from a listener method is caught by {@code AbstractAgentRuntime#fireXxx}
 * helpers and logged at TRACE level so one broken listener cannot abort the
 * execution pipeline (per Correctness Invariant #2).</p>
 */
public interface AgentLifecycleListener {

    /** Fired once at the start of an execution, before any model call. */
    default void onStart(AgentExecutionContext context) { }

    /**
     * Fired when the model asks for a tool invocation. {@code arguments} is
     * the decoded JSON argument map the runtime is about to pass to the tool
     * executor.
     */
    default void onToolCall(String toolName, Map<String, Object> arguments) { }

    /**
     * Fired when a tool invocation has produced a result (or error string).
     * {@code resultPreview} is a short string suitable for logs.
     */
    default void onToolResult(String toolName, String resultPreview) { }

    /**
     * Fired once when the execution has reported a final response to the
     * streaming session (i.e. {@code session.complete()}).
     */
    default void onCompletion(AgentExecutionContext context) { }

    /** Fired when the runtime reports an error on the streaming session. */
    default void onError(AgentExecutionContext context, Throwable error) { }

    /**
     * Fired when the runtime is about to issue a model call. {@code messageCount}
     * is the number of messages in the assembled prompt (including history and
     * system prompt). {@code toolCount} is the number of tool definitions
     * advertised on the request. Runtime bridges fire this immediately before
     * the underlying framework dispatches to the LLM — same observation point
     * Spring AI's {@code ChatClientObservation}, LangChain4j's
     * {@code ChatModelListener.onRequest}, ADK's {@code BeforeModelCallback},
     * and Koog's {@code PromptExecutorInterceptor} cover.
     *
     * <p>Only the Built-in runtime fires this today (via
     * {@code OpenAiCompatibleClient}). Framework runtimes inherit their own
     * native observability surfaces and should bridge into this hook from
     * their adapter module — see {@code modules/ai/README.md} for the
     * cross-runtime adoption status.</p>
     */
    default void onModelStart(String model, int messageCount, int toolCount) { }

    /**
     * Fired when the runtime received a final response from a model call.
     * {@code usage} is the typed token-usage record (may be null if the
     * provider did not emit usage data). {@code durationMillis} is the
     * wall-clock time spent in the model dispatch — useful for latency
     * histograms.
     */
    default void onModelEnd(String model,
                            org.atmosphere.ai.TokenUsage usage,
                            long durationMillis) { }

    /**
     * Fired when a model dispatch failed at the transport or provider layer
     * (network error, auth failure, server 5xx, parsing error). Distinct from
     * {@link #onError}, which fires for any error reported on the streaming
     * session — this hook narrows to model dispatch failures so observability
     * consumers can compute provider error rates without conflating
     * application-side errors.
     */
    default void onModelError(String model, Throwable error) { }

    /**
     * Invoke {@link #onToolCall} on every listener in the list, catching and
     * swallowing any exception so one broken listener cannot abort the
     * execution pipeline. Runtime bridges call this inside their tool-call
     * loop — the {@code AbstractAgentRuntime.fireXxx} helpers are
     * protected-static and cannot be reached from adapter modules, so this
     * public static mirror gives {@code SpringAiToolBridge},
     * {@code ToolAwareStreamingResponseHandler}, {@code AdkToolBridge},
     * {@code OpenAiCompatibleClient}, and {@code AtmosphereToolBridge}
     * (Koog) a single dispatch point.
     */
    static void fireToolCall(List<AgentLifecycleListener> listeners,
                             String toolName, Map<String, Object> arguments) {
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (var listener : listeners) {
            try {
                listener.onToolCall(toolName, arguments);
            } catch (Exception ignored) {
                // Listener exceptions never abort the execution pipeline
                // (Correctness Invariant #2 — Terminal Path Completeness).
                // Per-listener failures land in trace logs via the
                // AbstractAgentRuntime-scoped logger when the framework
                // fires equivalents from its own side.
            }
        }
    }

    /**
     * Invoke {@link #onToolResult} on every listener in the list. Same
     * swallow-and-continue semantics as {@link #fireToolCall}.
     */
    static void fireToolResult(List<AgentLifecycleListener> listeners,
                               String toolName, String resultPreview) {
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (var listener : listeners) {
            try {
                listener.onToolResult(toolName, resultPreview);
            } catch (Exception ignored) {
                // see fireToolCall javadoc
            }
        }
    }

    /**
     * Invoke {@link #onModelStart} on every listener in the list. Same
     * swallow-and-continue semantics as {@link #fireToolCall}.
     */
    static void fireModelStart(List<AgentLifecycleListener> listeners,
                               String model, int messageCount, int toolCount) {
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (var listener : listeners) {
            try {
                listener.onModelStart(model, messageCount, toolCount);
            } catch (Exception ignored) {
                // see fireToolCall javadoc
            }
        }
    }

    /**
     * Invoke {@link #onModelEnd} on every listener in the list. Same
     * swallow-and-continue semantics as {@link #fireToolCall}.
     */
    static void fireModelEnd(List<AgentLifecycleListener> listeners,
                             String model,
                             org.atmosphere.ai.TokenUsage usage,
                             long durationMillis) {
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (var listener : listeners) {
            try {
                listener.onModelEnd(model, usage, durationMillis);
            } catch (Exception ignored) {
                // see fireToolCall javadoc
            }
        }
    }

    /**
     * Invoke {@link #onModelError} on every listener in the list. Same
     * swallow-and-continue semantics as {@link #fireToolCall}.
     */
    static void fireModelError(List<AgentLifecycleListener> listeners,
                               String model, Throwable error) {
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (var listener : listeners) {
            try {
                listener.onModelError(model, error);
            } catch (Exception ignored) {
                // see fireToolCall javadoc
            }
        }
    }
}
