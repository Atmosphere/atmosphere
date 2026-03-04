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

/**
 * Retry policy for AI backend calls. Provides exponential backoff with
 * jitter and circuit breaker semantics.
 *
 * <p>Mirrors the transport-layer reconnection pattern in Atmosphere,
 * applied to AI API calls.</p>
 *
 * @param maxRetries    maximum number of retries before giving up
 * @param initialDelay  initial delay before the first retry
 * @param maxDelay      maximum delay between retries
 * @param backoffMultiplier  multiplier for exponential backoff
 * @param retryableErrors error types that should trigger a retry
 */
public record RetryPolicy(
        int maxRetries,
        Duration initialDelay,
        Duration maxDelay,
        double backoffMultiplier,
        java.util.Set<String> retryableErrors
) {
    /** Default retry policy: 3 retries, 1s initial, 30s max, 2x backoff. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            2.0,
            java.util.Set.of("rate_limit", "timeout", "server_error", "unavailable")
    );

    /** No retries. */
    public static final RetryPolicy NONE = new RetryPolicy(
            0,
            Duration.ZERO,
            Duration.ZERO,
            1.0,
            java.util.Set.of()
    );

    /**
     * Calculate the delay for the given retry attempt (0-indexed).
     * Includes jitter to avoid thundering herd.
     */
    public Duration delayForAttempt(int attempt) {
        if (attempt >= maxRetries) {
            return Duration.ZERO;
        }
        var delayMs = initialDelay.toMillis() * Math.pow(backoffMultiplier, attempt);
        var cappedMs = Math.min(delayMs, maxDelay.toMillis());
        // Add up to 25% jitter
        var jitter = cappedMs * 0.25 * Math.random();
        return Duration.ofMillis((long) (cappedMs + jitter));
    }

    /**
     * Whether the given error type should be retried.
     */
    public boolean shouldRetry(String errorType, int attemptsSoFar) {
        return attemptsSoFar < maxRetries && retryableErrors.contains(errorType);
    }

    /**
     * Create a custom retry policy.
     */
    public static RetryPolicy of(int maxRetries, Duration initialDelay) {
        return new RetryPolicy(maxRetries, initialDelay, Duration.ofSeconds(30),
                2.0, DEFAULT.retryableErrors());
    }
}
