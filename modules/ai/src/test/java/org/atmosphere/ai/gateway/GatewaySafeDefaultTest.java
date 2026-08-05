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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the default gateway.
 *
 * <p>The interesting property is not the numbers — it is that the shared
 * anonymous bucket sits <em>above</em> the per-principal ceiling rather than
 * below it. {@code GatewayProfiles.production()} derives anonymous as
 * {@code max / 4}, which is right when callers are authenticated and each has
 * their own bucket. With no authentication configured every caller collapses to
 * {@link AiGateway#ANONYMOUS_USER}, so that same derivation turns a
 * per-person ceiling into a whole-deployment one — adopting it as the framework
 * default would have 429'd the first demo anyone ran, trading an unenforced
 * limit for a self-inflicted outage.</p>
 */
class GatewaySafeDefaultTest {

    @Test
    void theSharedAnonymousBucketIsLargerThanThePerPrincipalCeiling() {
        assertTrue(GatewayProfiles.SAFE_DEFAULT_ANONYMOUS_MAX_REQUESTS
                        > GatewayProfiles.SAFE_DEFAULT_MAX_REQUESTS_PER_WINDOW,
                "without auth every caller is ANONYMOUS_USER, so this bucket is a "
                        + "whole-deployment ceiling — sizing it below the per-principal "
                        + "one is how the production profile would break an unauthenticated "
                        + "deployment");
    }

    @Test
    void unauthenticatedTrafficSurvivesFarPastThePerPrincipalCeiling() {
        var gateway = GatewayProfiles.safeDefault();

        // Well past the per-principal ceiling, but inside the anonymous bucket:
        // an unauthenticated demo must not be refused.
        for (var i = 0; i < GatewayProfiles.SAFE_DEFAULT_MAX_REQUESTS_PER_WINDOW + 200; i++) {
            assertTrue(gateway.admit(AiGateway.ANONYMOUS_USER, "built-in", "m").accepted(),
                    "anonymous traffic must not be capped at the per-principal ceiling");
        }
    }

    @Test
    void anonymousTrafficIsStillBoundedEventually() {
        var gateway = GatewayProfiles.safeDefault();

        var refused = false;
        for (var i = 0; i < GatewayProfiles.SAFE_DEFAULT_ANONYMOUS_MAX_REQUESTS * 2; i++) {
            if (!gateway.admit(AiGateway.ANONYMOUS_USER, "built-in", "m").accepted()) {
                refused = true;
                break;
            }
        }
        assertTrue(refused,
                "a larger bucket is still a bucket — an unbounded one would just be "
                        + "the old one-million-per-hour default wearing a new name");
    }

    @Test
    void oneNoisyPrincipalDoesNotConsumeAnothersBudget() {
        var gateway = GatewayProfiles.safeDefault();

        for (var i = 0; i < GatewayProfiles.SAFE_DEFAULT_MAX_REQUESTS_PER_WINDOW; i++) {
            gateway.admit("noisy", "built-in", "m");
        }

        assertFalse(gateway.admit("noisy", "built-in", "m").accepted(),
                "the noisy principal must be at its ceiling for this test to mean anything");
        assertTrue(gateway.admit("quiet", "built-in", "m").accepted(),
                "per-principal means per principal — one caller must not exhaust another's");
    }

    @Test
    void productionRemainsTighterThanTheDefault() {
        assertTrue(GatewayProfiles.PRODUCTION_MAX_REQUESTS_PER_WINDOW
                        < GatewayProfiles.SAFE_DEFAULT_MAX_REQUESTS_PER_WINDOW,
                "the documented opt-in must still buy something — if the default were "
                        + "tighter than production, the profile would be pointless");
    }
}
