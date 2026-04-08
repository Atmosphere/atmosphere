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

import java.time.Duration;

/**
 * Pluggable circuit breaker interface for fleet agent resilience.
 * Follows the same SPI pattern as {@code AgentRuntime}, {@code CoordinationJournal},
 * and {@code ResultEvaluator} — ships with a sensible default
 * ({@link DefaultCircuitBreaker}), discoverable via {@code ServiceLoader}.
 *
 * <p>Users who already have Resilience4j (or Sentinel, etc.) on their classpath
 * can implement this interface to delegate to their existing infrastructure.</p>
 *
 * @see DefaultCircuitBreaker
 */
public interface CircuitBreaker {

    /** Circuit breaker states. */
    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Configuration for circuit breaker behavior. */
    record Config(int failureThreshold, Duration openDuration, int halfOpenAttempts) {
        /** Default: open after 5 consecutive failures, stay open for 30s, probe with 1 attempt. */
        public static final Config DEFAULT = new Config(5, Duration.ofSeconds(30), 1);
    }

    /** Whether a request should be allowed through. */
    boolean allowRequest();

    /** Record a successful call. */
    void recordSuccess();

    /** Record a failed call. */
    void recordFailure();

    /** Current circuit state. */
    State state();
}
