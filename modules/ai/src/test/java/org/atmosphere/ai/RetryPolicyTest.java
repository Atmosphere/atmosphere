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

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RetryPolicy}.
 */
public class RetryPolicyTest {

    @Test
    public void testDefaultPolicy() {
        var policy = RetryPolicy.DEFAULT;
        assertEquals(3, policy.maxRetries());
        assertEquals(Duration.ofSeconds(1), policy.initialDelay());
        assertEquals(Duration.ofSeconds(30), policy.maxDelay());
        assertEquals(2.0, policy.backoffMultiplier());
    }

    @Test
    public void testNonePolicy() {
        var policy = RetryPolicy.NONE;
        assertEquals(0, policy.maxRetries());
        assertFalse(policy.shouldRetry("rate_limit", 0));
    }

    @Test
    public void testShouldRetryWithinLimit() {
        var policy = RetryPolicy.DEFAULT;
        assertTrue(policy.shouldRetry("rate_limit", 0));
        assertTrue(policy.shouldRetry("rate_limit", 1));
        assertTrue(policy.shouldRetry("rate_limit", 2));
        assertFalse(policy.shouldRetry("rate_limit", 3));
    }

    @Test
    public void testShouldRetryNonRetryableError() {
        var policy = RetryPolicy.DEFAULT;
        assertFalse(policy.shouldRetry("authentication_error", 0));
    }

    @Test
    public void testDelayExponentialBackoff() {
        var policy = RetryPolicy.DEFAULT;

        // Attempt 0: ~1000ms (1s * 2^0) + jitter
        var delay0 = policy.delayForAttempt(0);
        assertTrue(delay0.toMillis() >= 1000);
        assertTrue(delay0.toMillis() <= 1250); // max 25% jitter

        // Attempt 1: ~2000ms (1s * 2^1) + jitter
        var delay1 = policy.delayForAttempt(1);
        assertTrue(delay1.toMillis() >= 2000);
        assertTrue(delay1.toMillis() <= 2500);

        // Attempt 2: ~4000ms (1s * 2^2) + jitter
        var delay2 = policy.delayForAttempt(2);
        assertTrue(delay2.toMillis() >= 4000);
        assertTrue(delay2.toMillis() <= 5000);
    }

    @Test
    public void testDelayBeyondMaxRetriesReturnsZero() {
        var policy = RetryPolicy.DEFAULT;
        assertEquals(Duration.ZERO, policy.delayForAttempt(3));
        assertEquals(Duration.ZERO, policy.delayForAttempt(10));
    }

    @Test
    public void testDelayCappedAtMax() {
        var policy = new RetryPolicy(10, Duration.ofSeconds(10), Duration.ofSeconds(5), 3.0,
                java.util.Set.of("error"));
        // 10s * 3^2 = 90s, capped at 5s + jitter
        var delay = policy.delayForAttempt(2);
        assertTrue(delay.toMillis() <= 6250); // 5000 + 25% jitter
    }

    @Test
    public void testOfFactory() {
        var policy = RetryPolicy.of(5, Duration.ofMillis(500));
        assertEquals(5, policy.maxRetries());
        assertEquals(Duration.ofMillis(500), policy.initialDelay());
        assertEquals(Duration.ofSeconds(30), policy.maxDelay());
        assertEquals(2.0, policy.backoffMultiplier());
        // Inherits default retryable errors
        assertTrue(policy.shouldRetry("rate_limit", 0));
    }
}
