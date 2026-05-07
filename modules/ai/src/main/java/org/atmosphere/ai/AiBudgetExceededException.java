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

import java.util.Objects;

/**
 * Raised by {@link BudgetCapturingSession} when an in-flight call exceeds
 * one of the limits in its {@link AiBudget}. The exception is routed
 * through {@link StreamingSession#error(Throwable)} (matching the
 * guardrail-block path in {@link AiPipeline}) so observers, the wire
 * protocol's error frame, and lifecycle listeners all see a consistent
 * abort signal.
 *
 * <p>The {@link Reason} enum captures which limit tripped, and the
 * {@code observed} / {@code limit} pair lets callers (dashboards,
 * audit logs, automated retry policies) reason about how badly the
 * budget was breached without re-parsing the message.</p>
 *
 * <p>This is distinct from
 * {@link org.atmosphere.ai.budget.BudgetExceededException}, which is
 * raised by the per-tenant {@link org.atmosphere.ai.budget.StreamingTextBudgetManager}
 * when a long-running (per-month, per-user) streaming-text budget is
 * exhausted. This exception is the per-call circuit breaker — it fires
 * inside a single {@link AiPipeline#execute} invocation when the
 * conversation's token / step / wall-clock spend crosses the configured
 * envelope. Different scope, different lifetime, both extend
 * {@link AiException} so callers can {@code catch (AiException)} for a
 * uniform abort handler.</p>
 */
public final class AiBudgetExceededException extends AiException {

    private static final long serialVersionUID = 1L;

    /** Which budget dimension triggered the abort. */
    public enum Reason {
        /** Cumulative input/prompt tokens exceeded {@link AiBudget#maxInputTokens()}. */
        INPUT_TOKENS,
        /** Cumulative output/completion tokens exceeded {@link AiBudget#maxOutputTokens()}. */
        OUTPUT_TOKENS,
        /** Cumulative total tokens exceeded {@link AiBudget#maxTotalTokens()}. */
        TOTAL_TOKENS,
        /** {@link StreamingSession#usage(TokenUsage)} fired more often than {@link AiBudget#maxSteps()}. */
        STEPS,
        /** Wall-clock duration exceeded {@link AiBudget#maxWallClock()}. */
        WALL_CLOCK
    }

    private final Reason reason;
    private final long observed;
    private final long limit;

    public AiBudgetExceededException(Reason reason, long observed, long limit) {
        super(buildMessage(reason, observed, limit));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.observed = observed;
        this.limit = limit;
    }

    public Reason reason() { return reason; }

    /** The accumulated value that tripped the limit (tokens, steps, or
     * elapsed milliseconds depending on {@link #reason()}). */
    public long observed() { return observed; }

    /** The configured ceiling that {@link #observed()} crossed. */
    public long limit() { return limit; }

    private static String buildMessage(Reason reason, long observed, long limit) {
        var unit = reason == Reason.WALL_CLOCK ? "ms"
                : reason == Reason.STEPS ? "steps"
                : "tokens";
        return "AI budget exceeded: " + reason + " observed=" + observed + unit
                + " > limit=" + limit + unit;
    }
}
