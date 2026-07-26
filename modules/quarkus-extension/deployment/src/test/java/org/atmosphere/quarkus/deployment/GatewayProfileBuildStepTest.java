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
package org.atmosphere.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.GatewayProfiles;
import org.atmosphere.quarkus.runtime.AtmosphereGatewayProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarkus half of the gateway-profile parity pin (Spring side:
 * {@code AtmosphereGatewayProfileAutoConfigurationTest}). Boots with
 * {@code quarkus.atmosphere.ai.gateway.profile=production} plus explicit
 * overrides and proves the process-wide {@link AiGatewayHolder} carries the
 * tightened per-principal ceiling and a separately-sized anonymous bucket.
 * Fails without {@code AtmosphereProcessor.registerGatewayProducer} (the bean
 * is never registered, so the holder keeps its permissive default).
 */
public class GatewayProfileBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(GatewayProfileBuildStepTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.atmosphere.ai.gateway.profile", "production")
            .overrideConfigKey("quarkus.atmosphere.ai.gateway.max-requests-per-window", "5")
            .overrideConfigKey("quarkus.atmosphere.ai.gateway.window-seconds", "60")
            .overrideConfigKey("quarkus.atmosphere.ai.gateway.anonymous-max-requests", "2")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    AtmosphereGatewayProducer producer;

    @Test
    public void holderCarriesTheProductionGateway() {
        assertEquals(GatewayProfiles.PRODUCTION, producer.profile(),
                "the production profile must have been resolved from config");
        assertNotNull(producer.installedGateway(),
                "the startup observer must have installed a gateway");
        assertSame(producer.installedGateway(), AiGatewayHolder.get(),
                "the holder must carry exactly what the producer installed");
    }

    @Test
    public void configuredLimitsAreTheOnesEnforced() {
        var gateway = AiGatewayHolder.get();
        assertEquals(5, gateway.rateLimiter().maxRequests());
        assertEquals(60, gateway.rateLimiter().window().toSeconds());
        assertTrue(gateway.hasDedicatedAnonymousLimiter(),
                "the production profile must size the anonymous bucket separately");
        assertEquals(2, gateway.anonymousRateLimiter().maxRequests());
    }

    @Test
    public void anonymousBucketExhaustsWithoutSpendingANamedPrincipalsBudget() {
        var gateway = AiGatewayHolder.get();

        assertTrue(gateway.admit(AiGateway.ANONYMOUS_USER, "p", "m").accepted());
        assertTrue(gateway.admit(AiGateway.ANONYMOUS_USER, "p", "m").accepted());
        var rejected = gateway.admit(AiGateway.ANONYMOUS_USER, "p", "m");
        assertFalse(rejected.accepted(),
                "all unauthenticated callers share one bucket capped at 2");
        assertTrue(rejected.reason().contains("anonymous"),
                "the rejection must name the anonymous bucket; got: " + rejected.reason());

        // Distinct id, its own window — proves the buckets are separate.
        assertTrue(gateway.admit("alice", "p", "m").accepted(),
                "a named principal must be unaffected by anonymous exhaustion");
    }
}
