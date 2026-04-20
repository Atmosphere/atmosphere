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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the {@link PerUserRateLimiter} memory-leak fix.
 * Prior to the fix the per-user timestamp map had no eviction — anonymous or
 * session-scoped user ids grew the map without bound (Correctness
 * Invariant #3).
 */
class PerUserRateLimiterTest {

    /** A {@link Clock} whose current time can be set in tests. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant =
                new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        void advance(Duration d) {
            instant.updateAndGet(i -> i.plus(d));
        }
    }

    @Test
    void sweepEvictsIdleUsersWhoHaveNotCalledInThisWindow() {
        var clock = new MutableClock();
        var limiter = new PerUserRateLimiter(5, Duration.ofSeconds(60), clock);

        // Hammer 1,000 distinct user ids — typical of anonymous session ids.
        for (int i = 0; i < 1_000; i++) {
            assertTrue(limiter.tryAcquire("anon-" + i),
                    "first call for a novel user must always be accepted");
        }
        assertEquals(1_000, limiter.trackedUserCount(),
                "every distinct user is tracked immediately after acquisition");

        // Advance past the window — every timestamp is now outside it.
        clock.advance(Duration.ofSeconds(61));

        // Sweep: evicts every user whose deque drained empty.
        var evicted = limiter.sweep();
        assertEquals(1_000, evicted, "sweep must evict every idle user");
        assertEquals(0, limiter.trackedUserCount(),
                "map must shrink to zero after every user ages out — the leak "
                        + "fix: an unbounded Map<userId, Deque<Long>> is a DoS vector");
    }

    @Test
    void currentUsageEvictsIdleUserOnRead() {
        var clock = new MutableClock();
        var limiter = new PerUserRateLimiter(3, Duration.ofSeconds(10), clock);

        limiter.tryAcquire("alice");
        assertEquals(1, limiter.trackedUserCount());

        clock.advance(Duration.ofSeconds(11));

        // A currentUsage() call that observes an empty (drained) deque is
        // the public hook that lets admin/health endpoints clean up idle
        // users without waiting for the background sweep.
        assertEquals(0, limiter.currentUsage("alice"));
        assertEquals(0, limiter.trackedUserCount(),
                "currentUsage() must evict the entry when the deque is empty");
    }

    @Test
    void activeUsersRemainInMapAfterSweep() {
        var clock = new MutableClock();
        var limiter = new PerUserRateLimiter(3, Duration.ofSeconds(10), clock);

        // Two users acquire early, then time advances 6s — still inside window.
        limiter.tryAcquire("alice");
        limiter.tryAcquire("bob");
        clock.advance(Duration.ofSeconds(6));

        // A third user acquires now and a sweep runs — alice and bob still
        // have live timestamps inside the window so they must not be evicted.
        limiter.tryAcquire("carol");
        var evicted = limiter.sweep();

        assertEquals(0, evicted, "sweep must not touch users with live timestamps");
        assertEquals(3, limiter.trackedUserCount(),
                "recently-active users must stay in the map");
    }

    @Test
    void hardCapFailsClosedForNovelUsersWhenMapIsSaturated() {
        var clock = new MutableClock();
        // Tiny cap so the test saturates in a few iterations.
        var limiter = new PerUserRateLimiter(5, Duration.ofSeconds(60), clock, 3);

        // Fill the map to the cap with still-active users.
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("user-" + i));
        }
        assertEquals(3, limiter.trackedUserCount());

        // Previously this case failed OPEN — a DoS vector. Now it fails
        // CLOSED: once the cap is reached and sweep() can't reclaim a slot
        // (all entries are fresh), a novel user is rejected. Operators who
        // expect more users set a larger cap at construction time; the
        // default (100k) is ample for most deployments.
        assertFalse(limiter.tryAcquire("novel-user"),
                "beyond-cap novel users must fail closed once sweep() can't reclaim a slot");
        assertEquals(3, limiter.trackedUserCount(),
                "hard cap prevents unbounded map growth");

        // Advance the clock past the window so sweep() can reclaim slots —
        // the same novel user should now be admitted.
        clock.advance(Duration.ofMinutes(2));
        assertTrue(limiter.tryAcquire("novel-user"),
                "after sweep reclaims expired slots, the novel user is admitted");
    }

    @Test
    void existingUsersStillRateLimitedAfterCapReached() {
        var clock = new MutableClock();
        var limiter = new PerUserRateLimiter(2, Duration.ofSeconds(60), clock, 3);

        // Saturate the cap and exhaust alice's budget.
        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertFalse(limiter.tryAcquire("alice"),
                "alice's 3rd call must be rejected — her deque is full");

        limiter.tryAcquire("bob");
        limiter.tryAcquire("carol");
        assertEquals(3, limiter.trackedUserCount());

        // Cap is reached, but alice is already tracked — her rate limit
        // continues to apply, it does NOT bypass to fail-open.
        assertFalse(limiter.tryAcquire("alice"),
                "rate-limited tracked users must stay rate-limited even once "
                        + "the tracked-user cap is reached");
    }
}
