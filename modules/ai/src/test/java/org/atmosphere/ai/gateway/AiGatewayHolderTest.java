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
    void defaultGatewayAcceptsUnconditionally() {
        var gateway = AiGatewayHolder.get();
        assertNotNull(gateway);
        for (var i = 0; i < 1000; i++) {
            assertTrue(gateway.admit("u1", "built-in", "m").accepted(),
                    "permissive default must accept dev-scale traffic");
        }
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
