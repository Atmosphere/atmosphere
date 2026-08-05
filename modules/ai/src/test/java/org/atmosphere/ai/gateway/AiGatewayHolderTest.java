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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGatewayHolderTest {

    @AfterEach
    void resetHolder() {
        AiGatewayHolder.reset();
    }

    @Test
    void defaultGatewayAdmitsRealTrafficButIsNotUnbounded() {
        var gateway = AiGatewayHolder.get();
        assertNotNull(gateway);

        // Interactive use must be untouched. 200 requests inside one window is
        // far beyond what a person generates and still well under the ceiling.
        for (var i = 0; i < 200; i++) {
            assertTrue(gateway.admit("u1", "built-in", "m").accepted(),
                    "the default must be invisible to real traffic");
        }

        // The posture changed deliberately: the previous default of one million
        // calls per hour never fired, so the only thing between a runaway tool
        // loop and an unbounded provider bill was a startup WARN. A ceiling that
        // exists is the point — a pathological loop from one principal must
        // eventually be refused.
        var refused = false;
        for (var i = 0; i < GatewayProfiles.SAFE_DEFAULT_MAX_REQUESTS_PER_WINDOW * 2; i++) {
            if (!gateway.admit("u1", "built-in", "m").accepted()) {
                refused = true;
                break;
            }
        }
        assertTrue(refused,
                "the default must bound a runaway loop; an unenforced limit is not a limit");
    }

    @Test
    void installReplacesTheHolder() {
        var traces = new ArrayList<AiGateway.GatewayTraceEntry>();
        var tight = new AiGateway(
                new PerUserRateLimiter(1, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(),
                traces::add);
        AiGatewayHolder.install(tight);

        assertTrue(AiGatewayHolder.get().admit("u1", "p", "m").accepted());
        assertFalse(AiGatewayHolder.get().admit("u1", "p", "m").accepted(),
                "tight gateway caps at 1 call per user per hour");

        assertTrue(traces.stream().anyMatch(e -> !e.accepted()),
                "rejected admissions emit trace entries");
    }

    @Test
    void resetRestoresDefault() {
        AiGatewayHolder.install(new AiGateway(
                new PerUserRateLimiter(1, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop()));
        AiGatewayHolder.reset();

        // Default is permissive; two admissions must both succeed.
        assertTrue(AiGatewayHolder.get().admit("u1", "p", "m").accepted());
        assertTrue(AiGatewayHolder.get().admit("u1", "p", "m").accepted());
    }

    @Test
    void installRejectsNull() {
        assertThrows(NullPointerException.class, () -> AiGatewayHolder.install(null));
    }
}
