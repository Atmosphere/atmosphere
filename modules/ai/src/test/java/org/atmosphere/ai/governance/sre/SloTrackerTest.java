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
package org.atmosphere.ai.governance.sre;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloTrackerTest {

    @Test
    void freshTrackerHasEmptyBudgetConsumption() {
        var tracker = new SloTracker(ServiceLevelObjective.threeNines("test"));
        assertEquals(1.0, tracker.successRatio(), 1e-9,
                "no observations → success ratio defaults to 1.0");
        assertEquals(0.0, tracker.errorBudgetConsumed(), 1e-9);
        assertEquals(1.0, tracker.errorBudgetRemaining(), 1e-9);
        assertFalse(tracker.isBreached());
    }

    @Test
    void allSuccessesLeaveBudgetUntouched() {
        var tracker = new SloTracker(ServiceLevelObjective.threeNines("test"));
        for (int i = 0; i < 1000; i++) tracker.recordSuccess();
        assertEquals(1.0, tracker.successRatio(), 1e-9);
        assertEquals(0.0, tracker.errorBudgetConsumed(), 1e-9);
        assertFalse(tracker.isBreached());
    }

    @Test
    void failureRateAtBudgetIsOnTheLine() {
        // 99% target → error budget 1%. 1 failure out of 100 observations
        // is exactly at the budget — consumed = 1.0, not breached.
        var slo = new ServiceLevelObjective("test", 0.99, Duration.ofHours(1), "");
        var tracker = new SloTracker(slo);
        for (int i = 0; i < 99; i++) tracker.recordSuccess();
        tracker.recordFailure();
        assertEquals(0.99, tracker.successRatio(), 1e-9);
        assertEquals(1.0, tracker.errorBudgetConsumed(), 1e-9);
        assertFalse(tracker.isBreached(),
                "exactly at the limit is NOT breached (strict >1.0 test)");
    }

    @Test
    void failureRateOverBudgetIsBreached() {
        var slo = new ServiceLevelObjective("test", 0.99, Duration.ofHours(1), "");
        var tracker = new SloTracker(slo);
        for (int i = 0; i < 90; i++) tracker.recordSuccess();
        for (int i = 0; i < 10; i++) tracker.recordFailure();
        assertEquals(0.9, tracker.successRatio(), 1e-9);
        assertTrue(tracker.isBreached(),
                "10% failure rate breaches 99% target");
        assertTrue(tracker.errorBudgetConsumed() > 1.0);
        assertTrue(tracker.errorBudgetRemaining() < 0.0);
    }

    @Test
    void burnRateIsZeroWhenNoFailures() {
        var tracker = new SloTracker(ServiceLevelObjective.threeNines("test"));
        for (int i = 0; i < 100; i++) tracker.recordSuccess();
        assertEquals(0.0, tracker.burnRate(), 1e-9,
                "no failures → no burn");
    }

    @Test
    void burnRateAboveOneIndicatesPressure() {
        // 1% target error budget; observed 5% failure rate → burn 5x.
        var slo = new ServiceLevelObjective("test", 0.99, Duration.ofHours(1), "");
        var tracker = new SloTracker(slo);
        for (int i = 0; i < 95; i++) tracker.recordSuccess();
        for (int i = 0; i < 5; i++) tracker.recordFailure();
        assertEquals(5.0, tracker.burnRate(), 0.01,
                "5% observed / 1% allowed = 5x burn");
    }

    @Test
    void observationsOutsideWindowPruneAway() {
        var fixedNow = Instant.parse("2026-04-23T12:00:00Z");
        var mutable = new java.util.concurrent.atomic.AtomicReference<Instant>(fixedNow);
        var clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return mutable.get(); }
        };
        var slo = new ServiceLevelObjective("test", 0.99, Duration.ofMinutes(5), "");
        var tracker = new SloTracker(slo, clock);
        tracker.recordFailure();
        assertEquals(1, tracker.sampleCount());

        // Advance well past the window; the prior observation should prune.
        mutable.set(fixedNow.plus(Duration.ofMinutes(10)));
        assertEquals(0, tracker.sampleCount(),
                "observation outside window must be pruned");
        assertEquals(1.0, tracker.successRatio(), 1e-9);
    }

    @Test
    void preBakedFactoriesProduceValidSlos() {
        var three = ServiceLevelObjective.threeNines("a");
        assertEquals(0.999, three.target(), 1e-9);
        assertEquals(Duration.ofDays(30), three.window());

        var four = ServiceLevelObjective.fourNines("b");
        assertEquals(0.9999, four.target(), 1e-9);
    }

    @Test
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceLevelObjective("", 0.99, Duration.ofHours(1), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceLevelObjective("x", 0.0, Duration.ofHours(1), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceLevelObjective("x", 1.5, Duration.ofHours(1), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceLevelObjective("x", 0.99, Duration.ZERO, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new SloTracker(null));
    }
}
