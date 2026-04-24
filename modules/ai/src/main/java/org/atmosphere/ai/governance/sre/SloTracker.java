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
package org.atmosphere.ai.governance.sre;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling-window SLO accumulator. Each observation is either a success
 * or a failure; the tracker reports:
 *
 * <ul>
 *   <li>{@link #successRatio()} — observed success fraction in the window</li>
 *   <li>{@link #errorBudgetRemaining()} — fraction of the error budget
 *       that's still unused, in {@code [0, 1]}. Zero or negative means
 *       the SLO is currently breached for the window.</li>
 *   <li>{@link #burnRate()} — how fast failures are landing vs the
 *       steady-state pace. {@code > 2} means failures are outpacing the
 *       SLO's implied allowance; SREs use this for fast / slow burn
 *       alerts.</li>
 * </ul>
 *
 * <p>Thread-safe for single-writer / multi-reader concurrency (admission
 * thread records, metrics thread samples).</p>
 */
public final class SloTracker {

    private final ServiceLevelObjective slo;
    private final Clock clock;
    private final Deque<Observation> observations = new ArrayDeque<>();

    public SloTracker(ServiceLevelObjective slo) {
        this(slo, Clock.systemUTC());
    }

    /** Package-private for deterministic tests with a frozen clock. */
    SloTracker(ServiceLevelObjective slo, Clock clock) {
        if (slo == null) {
            throw new IllegalArgumentException("slo must not be null");
        }
        this.slo = slo;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public ServiceLevelObjective slo() {
        return slo;
    }

    /** Record a successful admission / dispatch / tool call. */
    public void recordSuccess() {
        synchronized (observations) {
            prune();
            observations.addLast(new Observation(clock.instant(), true));
        }
    }

    /** Record a failed admission / dispatch / tool call. */
    public void recordFailure() {
        synchronized (observations) {
            prune();
            observations.addLast(new Observation(clock.instant(), false));
        }
    }

    /** Observed success ratio over the window; {@code 1.0} when no data. */
    public double successRatio() {
        synchronized (observations) {
            prune();
            if (observations.isEmpty()) return 1.0;
            long total = observations.size();
            long success = observations.stream().filter(Observation::success).count();
            return (double) success / (double) total;
        }
    }

    /**
     * Error-budget consumption as a fraction of the allowed failure budget.
     * Zero means the budget is fully unused; 1.0 means exactly at the
     * limit; values above 1.0 mean we're over budget and the SLO is
     * currently breached.
     */
    public double errorBudgetConsumed() {
        synchronized (observations) {
            prune();
            if (observations.isEmpty()) return 0.0;
            long total = observations.size();
            long failures = total - observations.stream().filter(Observation::success).count();
            var observedFailureRate = (double) failures / (double) total;
            var budget = slo.errorBudget();
            if (budget == 0.0) {
                return failures > 0 ? Double.POSITIVE_INFINITY : 0.0;
            }
            return observedFailureRate / budget;
        }
    }

    /**
     * Remaining error-budget fraction, in {@code [-∞, 1]}. Clamped at
     * the caller; negative values surface as "we're breach-deep" so
     * operators can show the depth of the excursion.
     */
    public double errorBudgetRemaining() {
        return 1.0 - errorBudgetConsumed();
    }

    /** Whether the window currently violates the target. */
    public boolean isBreached() {
        return errorBudgetConsumed() > 1.0;
    }

    /**
     * Burn rate — ratio of observed failure rate to the steady-state rate
     * the SLO implies. {@code 1.0} = on track; {@code 2.0} = fast burn.
     * Returns {@code 0.0} when no failures have been observed (honest —
     * we can't infer burn from zero).
     */
    public double burnRate() {
        synchronized (observations) {
            prune();
            if (observations.isEmpty()) return 0.0;
            long total = observations.size();
            long failures = total - observations.stream().filter(Observation::success).count();
            if (failures == 0) return 0.0;
            var observedFailureRate = (double) failures / (double) total;
            var budgetRate = slo.errorBudget();
            if (budgetRate == 0.0) return Double.POSITIVE_INFINITY;
            return observedFailureRate / budgetRate;
        }
    }

    /** Number of observations currently held in the window. */
    public int sampleCount() {
        synchronized (observations) {
            prune();
            return observations.size();
        }
    }

    /** Drop observations older than the window's horizon. */
    private void prune() {
        var horizon = clock.instant().minus(slo.window());
        while (!observations.isEmpty()
                && observations.peekFirst().timestamp().isBefore(horizon)) {
            observations.removeFirst();
        }
    }

    /** Single observation — success flag + timestamp. */
    private record Observation(Instant timestamp, boolean success) { }
}
