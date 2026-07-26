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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-tool consecutive-failure breaker guarding remote MCP calls.
 * Without it, a remote server that has started failing every call is retried
 * on every turn at the full request timeout — ignoring an unmistakable
 * saturation signal (Correctness Invariant #3).
 */
class McpToolBreakerTest {

    @Test
    void closedBreakerAdmitsCalls() {
        var breaker = new McpToolBreaker(3, 1_000L);
        assertTrue(breaker.tryAcquire());
        assertEquals(McpToolBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void opensAfterTheConfiguredConsecutiveFailures() {
        var breaker = new McpToolBreaker(3, 60_000L);
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(McpToolBreaker.State.CLOSED, breaker.state(),
                "must not trip before the threshold");
        assertTrue(breaker.tryAcquire());

        breaker.recordFailure();
        assertEquals(McpToolBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire(), "an open breaker must reject calls");
        assertEquals(3, breaker.consecutiveFailures());
    }

    @Test
    void successResetsTheFailureCount() {
        var breaker = new McpToolBreaker(3, 60_000L);
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        assertEquals(0, breaker.consecutiveFailures());

        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(McpToolBreaker.State.CLOSED, breaker.state(),
                "the counter must restart after a success");
    }

    @Test
    void halfOpenProbeIsAdmittedAfterTheCooldown() throws Exception {
        var breaker = new McpToolBreaker(1, 50L);
        breaker.recordFailure();
        assertFalse(breaker.tryAcquire(), "open during the cooldown");

        Thread.sleep(80);
        assertEquals(McpToolBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.tryAcquire(), "one probe must be admitted after the cooldown");
        assertFalse(breaker.tryAcquire(), "only ONE probe may be in flight at a time");
    }

    @Test
    void successfulProbeClosesTheBreaker() throws Exception {
        var breaker = new McpToolBreaker(1, 50L);
        breaker.recordFailure();
        Thread.sleep(80);
        assertTrue(breaker.tryAcquire());

        breaker.recordSuccess();
        assertEquals(McpToolBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquire(), "a recovered server must be reachable again");
    }

    @Test
    void failedProbeReopensTheBreakerForAnotherCooldown() throws Exception {
        var breaker = new McpToolBreaker(1, 120L);
        breaker.recordFailure();
        Thread.sleep(150);
        assertTrue(breaker.tryAcquire(), "probe admitted");

        breaker.recordFailure();
        assertEquals(McpToolBreaker.State.OPEN, breaker.state(),
                "a failed probe must re-open, not close, the breaker");
        assertFalse(breaker.tryAcquire());
    }

    @Test
    void probeSlotIsReleasedByEveryRecordedOutcome() throws Exception {
        // Terminal-path completeness: a probe that records an outcome — either
        // one — must never strand the single probe slot. If it did, the breaker
        // would wedge and the remote tool would stay unreachable forever.
        var breaker = new McpToolBreaker(1, 40L);
        breaker.recordFailure();

        Thread.sleep(70);
        assertTrue(breaker.tryAcquire(), "first probe admitted");
        breaker.recordFailure();
        Thread.sleep(70);
        assertTrue(breaker.tryAcquire(),
                "a failed probe must release its slot so the next cooldown can probe again");
        breaker.recordSuccess();

        assertEquals(McpToolBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquire(), "and a successful probe must fully reopen the path");
    }

    @Test
    void zeroThresholdDisablesBreaking() {
        var breaker = new McpToolBreaker(0, 60_000L);
        assertFalse(breaker.enabled());
        for (int i = 0; i < 20; i++) {
            breaker.recordFailure();
        }
        assertTrue(breaker.tryAcquire(), "a disabled breaker must never reject");
        assertEquals(McpToolBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void optionsCarryTheBreakerSettingsWithSaneDefaults() {
        var defaults = McpClientOptions.defaults();
        assertEquals(McpClientOptions.DEFAULT_BREAKER_FAILURE_THRESHOLD,
                defaults.breakerFailureThreshold());
        assertEquals(McpClientOptions.DEFAULT_BREAKER_OPEN_MILLIS, defaults.breakerOpenMillis());
        assertTrue(defaults.breakerFailureThreshold() > 0,
                "breaking must be on by default — an opt-in breaker is no breaker");

        var custom = McpClientOptions.builder()
                .breakerFailureThreshold(2)
                .breakerOpenMillis(500L)
                .build();
        assertEquals(2, custom.breakerFailureThreshold());
        assertEquals(500L, custom.breakerOpenMillis());
    }
}
