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
package org.atmosphere.mcp.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tool consecutive-failure circuit breaker for remote MCP calls.
 *
 * <p>A remote MCP server that has started failing every call will keep failing
 * for the rest of the turn, and each attempt costs a full request timeout.
 * After {@code failureThreshold} consecutive failures the breaker opens and
 * subsequent calls fail immediately with a structured error instead of paying
 * the timeout again (Correctness Invariant #3 — stop hammering a source that
 * is signalling saturation).</p>
 *
 * <p>States:</p>
 * <ul>
 *   <li><b>closed</b> — calls pass through; a success resets the counter.</li>
 *   <li><b>open</b> — calls are rejected until {@code openMillis} has elapsed
 *       since the trip.</li>
 *   <li><b>half-open</b> — the first call after the cooldown is admitted as a
 *       probe. It closes the breaker on success and re-opens it (restarting
 *       the cooldown) on failure.</li>
 * </ul>
 *
 * <p>Thread-safe: state transitions are CAS-guarded so concurrent tool calls
 * admit exactly one half-open probe.</p>
 */
public final class McpToolBreaker {

    /** Breaker state as observed by {@link #state()}. */
    public enum State {
        /** Calls pass through. */
        CLOSED,
        /** Calls are rejected until the cooldown elapses. */
        OPEN,
        /** A single probe call is admitted. */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** Epoch millis at which the breaker may admit a probe; {@code 0} when closed. */
    private final AtomicLong openUntil = new AtomicLong();
    /** Set while a half-open probe is in flight so only one probe is admitted. */
    private final AtomicInteger probeInFlight = new AtomicInteger();

    /**
     * @param failureThreshold consecutive failures that trip the breaker;
     *                         {@code <= 0} disables it entirely
     * @param openMillis       cooldown before a half-open probe is admitted
     */
    public McpToolBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = failureThreshold;
        this.openMillis = Math.max(0L, openMillis);
    }

    /** Whether this breaker is enabled at all. */
    public boolean enabled() {
        return failureThreshold > 0;
    }

    /**
     * Try to admit a call. Returns {@code true} when the call may proceed
     * (closed, disabled, or an admitted half-open probe), {@code false} when
     * the breaker is open and the call must fail fast.
     */
    public boolean tryAcquire() {
        if (!enabled()) {
            return true;
        }
        var until = openUntil.get();
        if (until == 0L) {
            return true;
        }
        if (System.currentTimeMillis() < until) {
            return false;
        }
        // Cooldown elapsed — admit exactly one probe.
        return probeInFlight.compareAndSet(0, 1);
    }

    /** Record a successful call: closes the breaker and clears the counter. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil.set(0L);
        probeInFlight.set(0);
    }

    /**
     * Record a failed call. Trips the breaker once the consecutive-failure
     * count reaches the threshold; a failed half-open probe re-opens it and
     * restarts the cooldown.
     */
    public void recordFailure() {
        if (!enabled()) {
            return;
        }
        var failures = consecutiveFailures.incrementAndGet();
        if (probeInFlight.compareAndSet(1, 0) || failures >= failureThreshold) {
            openUntil.set(System.currentTimeMillis() + openMillis);
        }
    }

    /** Current state, for diagnostics and tests. */
    public State state() {
        var until = openUntil.get();
        if (until == 0L) {
            return State.CLOSED;
        }
        return System.currentTimeMillis() < until ? State.OPEN : State.HALF_OPEN;
    }

    /** Consecutive failures observed since the last success. */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
