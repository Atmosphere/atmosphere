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
package org.atmosphere.coordinator.fleet;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Built-in 3-state circuit breaker (~100 LOC, zero external deps).
 *
 * <p>State transitions:</p>
 * <ul>
 *   <li>{@code CLOSED} → {@code OPEN} after {@code failureThreshold} consecutive failures</li>
 *   <li>{@code OPEN} → {@code HALF_OPEN} after {@code openDuration} elapses</li>
 *   <li>{@code HALF_OPEN} → {@code CLOSED} on success, back to {@code OPEN} on failure</li>
 * </ul>
 *
 * <p>Thread-safe via {@link AtomicReference} for state and {@link AtomicInteger}
 * for counters. No locks, no blocking.</p>
 *
 * <p><b>Build vs buy rationale:</b> A 3-state breaker is ~100 lines. Resilience4j
 * adds 5+ transitive JARs and a surface area (bulkhead, rate-limiter, retry) we
 * don't use. The interface ({@link CircuitBreaker}) is pluggable — users who
 * already have Resilience4j can bring their own implementation.</p>
 */
public final class DefaultCircuitBreaker implements CircuitBreaker {

    private final Config config;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);
    private volatile Instant openedAt = Instant.MIN;

    public DefaultCircuitBreaker() {
        this(Config.DEFAULT);
    }

    public DefaultCircuitBreaker(Config config) {
        this.config = config;
    }

    @Override
    public boolean allowRequest() {
        return switch (state.get()) {
            case CLOSED -> true;
            case OPEN -> {
                if (Instant.now().isAfter(openedAt.plus(config.openDuration()))) {
                    // Cooldown expired — transition to half-open for probing
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenAttempts.set(0);
                    }
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> halfOpenAttempts.get() < config.halfOpenAttempts();
        };
    }

    @Override
    public void recordSuccess() {
        consecutiveFailures.set(0);
        halfOpenAttempts.set(0);
        state.set(State.CLOSED);
    }

    @Override
    public void recordFailure() {
        var currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            // Failed during probe — back to open
            openedAt = Instant.now();
            state.set(State.OPEN);
            return;
        }
        var failures = consecutiveFailures.incrementAndGet();
        if (failures >= config.failureThreshold()) {
            openedAt = Instant.now();
            state.set(State.OPEN);
        }
    }

    @Override
    public State state() {
        // Check if OPEN should transition to HALF_OPEN (for external state queries)
        if (state.get() == State.OPEN
                && Instant.now().isAfter(openedAt.plus(config.openDuration()))) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }
        return state.get();
    }
}
