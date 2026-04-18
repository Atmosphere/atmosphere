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
 *
 * <h2>Memory bounds</h2>
 *
 * User ids often come from untrusted sources (session tokens, anonymous
 * visitor handles) and the inbound set is effectively unbounded, so a
 * naive per-user map is a DoS vector (Correctness Invariant #3).
 * Two eviction layers protect the map:
 *
 * <ol>
 *   <li>When a user's deque drains below the cutoff it is removed from the
 *       map — idle users leave no residue.</li>
 *   <li>A hard cap ({@value #DEFAULT_MAX_TRACKED_USERS}) on distinct tracked
 *       users prevents a flood of novel ids from exhausting the heap. When
 *       the cap is reached the rate-limiter fails open (returns {@code true})
 *       to avoid cascading 429s; this is logged via the rate-limit
 *       rejection counter semantics upstream.</li>
 * </ol>
 */
public final class PerUserRateLimiter {

    /**
     * Default hard cap on distinct tracked user ids. Chosen so that a deque
     * of {@code maxRequests} longs per user fits comfortably on the heap
     * (~20 MB at 100k users × 16 timestamps × 8 bytes + overhead). Tune
     * down if your deployment is memory-constrained.
     */
    public static final int DEFAULT_MAX_TRACKED_USERS = 100_000;

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final int maxTrackedUsers;
    private final Map<String, Deque<Long>> timestamps = new ConcurrentHashMap<>();

    public PerUserRateLimiter(int maxRequests, Duration window) {
        this(maxRequests, window, Clock.systemUTC(), DEFAULT_MAX_TRACKED_USERS);
    }

    PerUserRateLimiter(int maxRequests, Duration window, Clock clock) {
        this(maxRequests, window, clock, DEFAULT_MAX_TRACKED_USERS);
    }

    PerUserRateLimiter(int maxRequests, Duration window, Clock clock, int maxTrackedUsers) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be > 0, got " + maxRequests);
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
        if (maxTrackedUsers <= 0) {
            throw new IllegalArgumentException(
                    "maxTrackedUsers must be > 0, got " + maxTrackedUsers);
        }
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
        this.maxTrackedUsers = maxTrackedUsers;
    }

    /**
     * Attempt to record a new request for this user. Returns {@code true} if
     * the request is within the rate limit, {@code false} if it should be
     * rejected.
     *
     * <p>When the tracked-user cap is reached for a previously-unseen user
     * the call fails open: we'd rather serve one extra request than 429 a
     * legitimate user because the limiter can't allocate a deque.</p>
     */
    public boolean tryAcquire(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        var now = clock.millis();
        var cutoff = now - window.toMillis();
        var existing = timestamps.get(userId);
        if (existing == null) {
            // Enforce hard cap before allocating a new deque.
            if (timestamps.size() >= maxTrackedUsers) {
                return true; // fail open — see javadoc
            }
            var fresh = timestamps.computeIfAbsent(userId, k -> new ArrayDeque<>());
            synchronized (fresh) {
                fresh.addLast(now);
            }
            return true;
        }
        synchronized (existing) {
            while (!existing.isEmpty() && existing.peekFirst() < cutoff) {
                existing.pollFirst();
            }
            if (existing.size() >= maxRequests) {
                return false;
            }
            existing.addLast(now);
            return true;
        }
    }

    /**
     * Current usage within the window for the given user. Drains any expired
     * timestamps and removes the user's entry from the map when the deque is
     * empty — this is the public hook that lets callers sweep idle users.
     */
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
            if (queue.isEmpty()) {
                // Evict the idle user (Correctness Invariant #3). Use
                // computeIfPresent so we don't race with a concurrent
                // tryAcquire() that just appended a timestamp for the same
                // user — if the deque is no longer empty, leave it alone.
                timestamps.computeIfPresent(userId, (k, e) -> {
                    synchronized (e) {
                        return e.isEmpty() ? null : e;
                    }
                });
                return 0;
            }
            return queue.size();
        }
    }

    /**
     * Evict every tracked user whose deque has fully drained outside the
     * window. Safe to call on a background scheduler; intended to be invoked
     * periodically (e.g. once per window) so idle users don't accumulate.
     * Returns the number of users evicted.
     */
    public int sweep() {
        var cutoff = clock.millis() - window.toMillis();
        var evicted = 0;
        // Iterate a snapshot of keys so we can mutate the map during the sweep.
        for (var userId : timestamps.keySet().toArray(new String[0])) {
            var queue = timestamps.get(userId);
            if (queue == null) {
                continue;
            }
            synchronized (queue) {
                while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
                    queue.pollFirst();
                }
                if (queue.isEmpty()) {
                    var removed = timestamps.computeIfPresent(userId, (k, e) -> {
                        synchronized (e) {
                            return e.isEmpty() ? null : e;
                        }
                    });
                    if (removed == null) {
                        evicted++;
                    }
                }
            }
        }
        return evicted;
    }

    /** Current number of distinct users being tracked. Visible for tests. */
    int trackedUserCount() {
        return timestamps.size();
    }

    public int maxRequests() {
        return maxRequests;
    }

    public Duration window() {
        return window;
    }
}
