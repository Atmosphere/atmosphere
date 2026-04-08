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
 * Pluggable retry strategy for agent calls. Extracted from the hardcoded
 * exponential backoff in {@link DefaultAgentProxy} to allow per-agent
 * retry customization.
 *
 * <p>Sealed to ensure exhaustive pattern matching in switch expressions.</p>
 */
public sealed interface RetryPolicy {

    /** No retries — fail immediately. */
    record NoRetry() implements RetryPolicy {}

    /** Exponential backoff: base * 2^(attempt-1). The current default behavior. */
    record ExponentialBackoff(Duration base, int maxRetries) implements RetryPolicy {}

    /** Linear backoff: fixed interval between retries. */
    record LinearBackoff(Duration interval, int maxRetries) implements RetryPolicy {}

    /** Compute the delay for the given attempt (1-based). Returns Duration.ZERO for NoRetry. */
    default Duration delayForAttempt(int attempt) {
        return switch (this) {
            case NoRetry ignored -> Duration.ZERO;
            case ExponentialBackoff eb ->
                    Duration.ofMillis(eb.base().toMillis() * (1L << (attempt - 1)));
            case LinearBackoff lb -> lb.interval();
        };
    }

    /** Maximum number of retry attempts. Returns 0 for NoRetry. */
    default int maxRetries() {
        return switch (this) {
            case NoRetry ignored -> 0;
            case ExponentialBackoff eb -> eb.maxRetries();
            case LinearBackoff lb -> lb.maxRetries();
        };
    }

    /** Create the default exponential backoff from a retry count (backward-compatible). */
    static RetryPolicy fromMaxRetries(int maxRetries) {
        if (maxRetries <= 0) {
            return new NoRetry();
        }
        return new ExponentialBackoff(Duration.ofMillis(100), maxRetries);
    }
}
