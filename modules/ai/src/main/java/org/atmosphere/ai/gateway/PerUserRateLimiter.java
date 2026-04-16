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
package org.atmosphere.ai.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-user sliding-window rate limiter. Tracks a moving window of
 * request timestamps per user and rejects calls that would exceed the
 * configured limit.
 *
 * <p>This is distinct from {@code StreamingTextBudgetManager} (which tracks
 * token counts) — this limiter caps the request rate regardless of how many
 * tokens each call produces. Both layers are consulted by {@link AiGateway}
 * before an LLM call dispatches.</p>
 *
 * <h2>Backpressure semantics</h2>
 *
 * {@link #tryAcquire(String)} returns {@code false} when the rate limit is
 * exceeded — callers MUST honor this (Correctness Invariant #3:
 * never ignore rejection signals). Typical callers translate a rejected
 * acquire into HTTP 429 or a streaming error frame.
 *
 * <h2>Thread safety</h2>
 *
 * All state is held in a {@link ConcurrentHashMap} of per-user deques. The
 * per-user deque is guarded by synchronizing on the deque instance itself,
 * which pairs one-to-one with the user id — no cross-user contention.
 */
public final class PerUserRateLimiter {

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Long>> timestamps = new ConcurrentHashMap<>();

    public PerUserRateLimiter(int maxRequests, Duration window) {
        this(maxRequests, window, Clock.systemUTC());
    }

    PerUserRateLimiter(int maxRequests, Duration window, Clock clock) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be > 0, got " + maxRequests);
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Attempt to record a new request for this user. Returns {@code true} if
     * the request is within the rate limit, {@code false} if it should be
     * rejected.
     */
    public boolean tryAcquire(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        var queue = timestamps.computeIfAbsent(userId, k -> new ArrayDeque<>());
        var now = clock.millis();
        var cutoff = now - window.toMillis();
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
                queue.pollFirst();
            }
            if (queue.size() >= maxRequests) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    /** Current usage within the window for the given user. */
    public int currentUsage(String userId) {
        var queue = timestamps.get(userId);
        if (queue == null) {
            return 0;
        }
        var cutoff = clock.millis() - window.toMillis();
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
                queue.pollFirst();
            }
            return queue.size();
        }
    }

    public int maxRequests() {
        return maxRequests;
    }

    public Duration window() {
        return window;
    }
}
