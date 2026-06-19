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
import java.util.function.Supplier;

/**
 * One per-LLM-call observation span. Collapses the copy-pasted
 * {@code startNanos}-capture + {@code (nanoTime - start) / 1_000_000L} duration
 * arithmetic + the three {@code fireModelStart} / {@code fireModelEnd} /
 * {@code fireModelError} fan-out calls that every runtime bridge repeats around
 * each model dispatch (LangChain4j, Spring AI, Spring AI Alibaba, ADK,
 * AgentScope, Semantic Kernel, Koog, Embabel, the Built-in
 * {@code OpenAiCompatibleClient}) into a single shared, statically-callable
 * helper.
 *
 * <p>This is purely a refactor of the boilerplate — it delegates verbatim to
 * the {@link AgentLifecycleListener} {@code fireModelStart} / {@code fireModelEnd}
 * / {@code fireModelError} statics and does <em>not</em> reimplement their
 * swallow-and-continue semantics. Event count, ordering, and the
 * "exactly one {@code onModelStart} + exactly one {@code onModelEnd} per
 * dispatch (or one {@code onModelError} on failure)" invariant are preserved
 * bit-for-bit: {@link #open} fires exactly one start, {@link #complete} fires
 * exactly one end, {@link #fail} fires exactly one error. The scope carries no
 * once-guard of its own — call sites already reach exactly one terminal fire on
 * each path, matching the hand-rolled code this replaces.</p>
 *
 * <p>The start time is captured inside {@link #open}, at the same instant the
 * start hook fires — so the reported {@code durationMillis} measures the same
 * dispatch window the inline code measured. Sites that previously captured
 * {@code System.nanoTime()} immediately before {@code fireModelStart} are
 * byte-identical; the one intentional normalization is ADK, whose
 * {@code AdkEventAdapter} formerly captured the start time in its constructor
 * (which runs just before {@code fireModelStart}) — moving it into
 * {@link #open} aligns ADK's start instant to the dispatch moment like every
 * peer. The delta is sub-microsecond and only affects the reported duration
 * value, never event count or ordering.</p>
 *
 * <p>The class is plain Java with a static factory and instance methods, so the
 * Kotlin runtimes (Koog, Embabel) — which do not extend
 * {@code AbstractAgentRuntime} — call it the same way as
 * {@code ModelCallScope.open(...)} / {@code scope.complete(...)} /
 * {@code scope.fail(...)} across the module boundary.</p>
 */
public final class ModelCallScope {

    private final List<AgentLifecycleListener> listeners;
    private final String model;
    private final long startNanos;

    private ModelCallScope(List<AgentLifecycleListener> listeners,
                           String model, long startNanos) {
        this.listeners = listeners;
        this.model = model;
        this.startNanos = startNanos;
    }

    /**
     * Open a model-call observation span: capture {@code System.nanoTime()} and
     * fire {@link AgentLifecycleListener#onModelStart} on every listener with
     * the supplied {@code model} / {@code messageCount} / {@code toolCount}. The
     * arguments are passed through verbatim — each call site keeps computing its
     * own message-count and tool-count exactly as before (they differ between
     * runtimes, e.g. Spring AI counts history + system prompt + user message,
     * LangChain4j uses the assembled message list size).
     *
     * @param listeners    the lifecycle listeners attached to the execution
     *                     (may be {@code null} or empty — then start is a no-op)
     * @param model        the resolved model name reported to observers
     * @param messageCount the assembled prompt message count for this dispatch
     * @param toolCount    the number of tool definitions advertised on the call
     * @return the open scope; call {@link #complete} or {@link #fail} on the
     *         matching terminal path
     */
    public static ModelCallScope open(List<AgentLifecycleListener> listeners,
                                      String model, int messageCount, int toolCount) {
        var startNanos = System.nanoTime();
        AgentLifecycleListener.fireModelStart(listeners, model, messageCount, toolCount);
        return new ModelCallScope(listeners, model, startNanos);
    }

    /**
     * Complete the span on the success path: compute the wall-clock duration
     * (nanoseconds since {@link #open}, floored to whole milliseconds) and fire
     * {@link AgentLifecycleListener#onModelEnd} carrying the captured token
     * usage (may be {@code null} when the provider reported none) and the
     * duration. Fires exactly one end event.
     *
     * @param usage the token-usage record for this dispatch, or {@code null}
     */
    public void complete(TokenUsage usage) {
        long durationMillis = durationMillis();
        AgentLifecycleListener.fireModelEnd(listeners, model, usage, durationMillis);
    }

    /**
     * Fail the span on the error path: fire
     * {@link AgentLifecycleListener#onModelError} with the throwable. Fires
     * exactly one error event. Mirrors the existing
     * {@code fireModelError(listeners, model, error)} signature — the
     * model-error hook carries no duration, so none is computed here (the
     * inline sites this replaces never passed a duration to
     * {@code fireModelError}).
     *
     * @param error the dispatch failure to report
     */
    public void fail(Throwable error) {
        AgentLifecycleListener.fireModelError(listeners, model, error);
    }

    /**
     * Wall-clock milliseconds elapsed since {@link #open}, floored to whole
     * millis — the same {@code (System.nanoTime() - startNanos) / 1_000_000L}
     * arithmetic the inline sites used. Exposed for the rare site that needs
     * the duration independently of {@link #complete}.
     *
     * @return non-negative elapsed milliseconds
     */
    public long durationMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Convenience for a simple synchronous dispatch: open the span, run
     * {@code body}, complete with {@code null} usage on success (return the
     * body's value), or {@link #fail} and rethrow on exception. Use this only
     * where the call is a self-contained block whose token usage is not
     * separately captured during streaming — sites that accumulate usage into a
     * holder during the stream should use {@link #open} + {@link #complete}
     * directly so the captured usage rides the end event.
     *
     * @param listeners    the lifecycle listeners (may be {@code null}/empty)
     * @param model        the resolved model name
     * @param messageCount the assembled prompt message count
     * @param toolCount    the advertised tool count
     * @param body         the synchronous dispatch to observe
     * @param <T>          the dispatch result type
     * @return the value returned by {@code body}
     */
    public static <T> T modelCall(List<AgentLifecycleListener> listeners,
                                  String model, int messageCount, int toolCount,
                                  Supplier<T> body) {
        var scope = open(listeners, model, messageCount, toolCount);
        try {
            T result = body.get();
            scope.complete(null);
            return result;
        } catch (RuntimeException | Error e) {
            scope.fail(e);
            throw e;
        }
    }
}
