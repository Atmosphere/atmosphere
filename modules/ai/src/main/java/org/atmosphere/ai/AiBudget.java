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
import java.util.Map;
import java.util.Objects;

/**
 * Per-call budget envelope for {@link AiPipeline} — the framework-level
 * circuit breaker that prevents "death spiral" runaway loops where an agent
 * burns thousands of dollars chasing a problem the model cannot solve.
 *
 * <p>Limits with a value of {@code 0} (or {@link Duration#ZERO} /
 * {@code null} for wall clock) are treated as <em>not enforced</em>; only
 * fields with a positive value contribute to the abort decision. Combine
 * fields freely — a budget with only {@code maxSteps=10} caps the
 * tool-calling loop at 10 round trips regardless of token usage; a budget
 * with only {@code maxTotalTokens=20_000} caps spend regardless of step
 * count.</p>
 *
 * <p>Threading: the budget is immutable. The
 * {@link org.atmosphere.ai.BudgetCapturingSession} decorator that consumes
 * it accumulates per-session counters and aborts via
 * {@link StreamingSession#error(Throwable)} with an
 * {@link AiBudgetExceededException}.</p>
 *
 * @param maxInputTokens  cap on cumulative prompt tokens reported by the
 *                        runtime via {@link TokenUsage#input()}; 0 = unenforced
 * @param maxOutputTokens cap on cumulative completion tokens reported via
 *                        {@link TokenUsage#output()}; 0 = unenforced
 * @param maxTotalTokens  cap on cumulative total tokens; 0 = unenforced
 * @param maxSteps        cap on the number of {@code usage()} callbacks (one
 *                        per runtime turn, including each tool-call
 *                        round-trip); 0 = unenforced
 * @param maxWallClock    cap on wall-clock duration of the call from the
 *                        moment the decorator is wrapped; {@code null} or
 *                        {@link Duration#ZERO} / negative = unenforced
 */
public record AiBudget(
        long maxInputTokens,
        long maxOutputTokens,
        long maxTotalTokens,
        int maxSteps,
        Duration maxWallClock
) {

    /** Metadata key used to thread a per-request {@link AiBudget} through
     * {@link AgentExecutionContext#metadata()} and {@link AiRequest#metadata()},
     * mirroring the pattern of {@code ai.cache.hint}. */
    public static final String METADATA_KEY = "ai.budget";

    /** A budget with every field unenforced — equivalent to "no budget". */
    public static final AiBudget UNLIMITED = new AiBudget(0L, 0L, 0L, 0, null);

    public AiBudget {
        if (maxInputTokens < 0) {
            throw new IllegalArgumentException("maxInputTokens must be >= 0");
        }
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must be >= 0");
        }
        if (maxTotalTokens < 0) {
            throw new IllegalArgumentException("maxTotalTokens must be >= 0");
        }
        if (maxSteps < 0) {
            throw new IllegalArgumentException("maxSteps must be >= 0");
        }
        if (maxWallClock != null && maxWallClock.isNegative()) {
            throw new IllegalArgumentException(
                    "maxWallClock must be null, zero, or positive (was " + maxWallClock + ")");
        }
    }

    /** Token-only budget: cap total tokens, leave step count and wall clock unenforced. */
    public static AiBudget ofTokens(long maxTotalTokens) {
        return new AiBudget(0L, 0L, maxTotalTokens, 0, null);
    }

    /** Step-only budget: cap the tool-loop / turn count, leave tokens unenforced. */
    public static AiBudget ofSteps(int maxSteps) {
        return new AiBudget(0L, 0L, 0L, maxSteps, null);
    }

    /** Wall-clock-only budget: cap wall time, leave tokens and steps unenforced. */
    public static AiBudget ofWallClock(Duration maxWallClock) {
        return new AiBudget(0L, 0L, 0L, 0, maxWallClock);
    }

    /** {@code true} when at least one field is enforced. Pipeline uses this
     * to decide whether to install the {@link BudgetCapturingSession}
     * decorator at all — an unlimited budget is the same as no budget. */
    public boolean enforced() {
        return maxInputTokens > 0
                || maxOutputTokens > 0
                || maxTotalTokens > 0
                || maxSteps > 0
                || (maxWallClock != null && !maxWallClock.isZero());
    }

    /** Extract a budget from the metadata map, falling back to {@code null}
     * (not {@link #UNLIMITED}) when no key is present so callers can
     * distinguish "no budget" from "unlimited budget". */
    public static AiBudget from(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        var value = metadata.get(METADATA_KEY);
        return value instanceof AiBudget budget ? budget : null;
    }

    /** Same as {@link #from(Map)} but reads from an
     * {@link AgentExecutionContext}; returns {@code null} when the context
     * has no budget metadata. */
    public static AiBudget from(AgentExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return from(context.metadata());
    }
}
