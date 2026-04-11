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
}
