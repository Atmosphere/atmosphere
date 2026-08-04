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
package org.atmosphere.ai.guardrails;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties that make a cost ceiling safe to switch on.
 *
 * <p>Tenant buckets are keyed by the {@code business.tenant.id} MDC tag, which
 * is caller-influenced. Unbounded, that is a request header turned into
 * unbounded heap growth (Invariant #3) — so enabling a ceiling by default
 * without a cap would trade a cost risk for a denial-of-service one.</p>
 *
 * <p>And accrual only ever went up: with no decay, the first tenant to cross
 * its budget was blocked for the lifetime of the process. A guard that
 * converts into a permanent outage is worse than no guard, so the window rolls
 * the counters over.</p>
 */
class CostCeilingBoundsTest {

    @Test
    void tenantBucketsAreCappedSoAnAttackerControlledTagCannotGrowTheMap() {
        var guardrail = new CostCeilingGuardrail(100.0, 3, Duration.ofDays(30));

        for (var i = 0; i < 500; i++) {
            guardrail.addCost("tenant-" + i, 1.0);
        }

        // 3 named buckets plus the shared default that overflow folds into —
        // bounded is the property that matters, and 500 distinct tags must not
        // produce 500 entries.
        assertTrue(guardrail.trackedTenants() <= 4,
                "500 distinct tenant tags must not grow the map past the cap "
                        + "(+1 shared default bucket); got " + guardrail.trackedTenants());
    }

    @Test
    void overflowTenantsStillAccrueRatherThanEscapingTheCeiling() {
        var guardrail = new CostCeilingGuardrail(100.0, 1, Duration.ofDays(30));

        guardrail.addCost("first", 5.0);
        // "second" cannot get its own bucket, so it must land in the shared one —
        // folding overflow into the default keeps it enforced, where dropping it
        // would let anyone past the ceiling just by inventing a new tenant id.
        guardrail.addCost("second", 7.0);

        assertTrue(guardrail.spent(null) >= 7.0,
                "overflow spend must land in the default bucket, not vanish; got "
                        + guardrail.spent(null));
    }

    @Test
    void accrualRollsOverOnceTheWindowElapses() throws Exception {
        var guardrail = new CostCeilingGuardrail(100.0, 10, Duration.ofMillis(50));

        guardrail.addCost("acme", 90.0);
        assertEquals(90.0, guardrail.spent("acme"), 0.001);

        Thread.sleep(80);

        assertEquals(0.0, guardrail.spent("acme"), 0.001,
                "spend must roll over after the window, otherwise a tenant that "
                        + "once hit its ceiling is blocked forever");
    }

    @Test
    void aZeroWindowDisablesRolloverForCallersThatWantManualBillingBoundaries() throws Exception {
        var guardrail = new CostCeilingGuardrail(100.0, 10, Duration.ZERO);

        guardrail.addCost("acme", 90.0);
        Thread.sleep(20);

        assertEquals(90.0, guardrail.spent("acme"), 0.001,
                "an explicitly disabled window must not silently reset the counter");
    }

    @Test
    void anInvalidCapIsRejectedRatherThanSilentlyUnbounding() {
        assertThrows(IllegalArgumentException.class,
                () -> new CostCeilingGuardrail(10.0, 0, Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new CostCeilingGuardrail(10.0, -1, Duration.ofDays(1)));
    }

    @Test
    void theDefaultConstructorIsBoundedAndRollsOver() {
        var guardrail = new CostCeilingGuardrail(50.0);

        for (var i = 0; i < 20; i++) {
            guardrail.addCost("t" + i, 1.0);
        }

        assertTrue(guardrail.trackedTenants() <= CostCeilingGuardrail.DEFAULT_MAX_TRACKED_TENANTS,
                "the plain constructor must inherit the cap — it is the one every "
                        + "existing caller already uses");
    }
}
