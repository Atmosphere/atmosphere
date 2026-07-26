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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the production gateway profile: the tightened per-principal ceiling,
 * the separately-sized shared anonymous bucket, and the guarantee that the
 * framework default stays permissive until an operator opts in.
 */
class GatewayProfilesTest {

    @AfterEach
    void resetHolder() {
        AiGatewayHolder.reset();
    }

    @Test
    void productionProfileInstallsTheDocumentedLimits() {
        var gateway = GatewayProfiles.production();

        assertEquals(GatewayProfiles.PRODUCTION_MAX_REQUESTS_PER_WINDOW,
                gateway.rateLimiter().maxRequests(),
                "the production profile must carry the documented per-principal ceiling");
        assertEquals(Duration.ofSeconds(GatewayProfiles.PRODUCTION_WINDOW_SECONDS),
                gateway.rateLimiter().window(),
                "the production profile must carry the documented window");
        assertTrue(gateway.hasDedicatedAnonymousLimiter(),
                "the production profile must size the anonymous bucket separately");
        assertEquals(
                GatewayProfiles.PRODUCTION_MAX_REQUESTS_PER_WINDOW
                        / GatewayProfiles.PRODUCTION_ANONYMOUS_DIVISOR,
                gateway.anonymousRateLimiter().maxRequests(),
                "the anonymous bucket must be penalized by the documented divisor");
    }

    @Test
    void productionProfileActuallyRejectsOverTheCeiling() {
        // A small explicit profile so the assertion drives real enforcement
        // rather than restating constants.
        var gateway = GatewayProfiles.production(3, Duration.ofMinutes(5), 0,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());

        assertTrue(gateway.admit("alice", "openai", "gpt-4o").accepted());
        assertTrue(gateway.admit("alice", "openai", "gpt-4o").accepted());
        assertTrue(gateway.admit("alice", "openai", "gpt-4o").accepted());

        var rejected = gateway.admit("alice", "openai", "gpt-4o");
        assertFalse(rejected.accepted(),
                "the production profile must enforce its per-principal ceiling");
        assertTrue(rejected.reason().contains("rate limit"), rejected.reason());
    }

    @Test
    void anonymousBucketIsSeparateFromNamedPrincipals() {
        // Anonymous capped at 1; named principals keep the (larger) main budget.
        var gateway = GatewayProfiles.production(10, Duration.ofMinutes(5), 1,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());

        assertTrue(gateway.admit(AiGateway.ANONYMOUS_USER, "openai", "gpt-4o").accepted());
        var secondAnonymous = gateway.admit(AiGateway.ANONYMOUS_USER, "openai", "gpt-4o");
        assertFalse(secondAnonymous.accepted(),
                "the shared anonymous bucket must exhaust at its own, tighter ceiling");
        assertTrue(secondAnonymous.reason().contains("anonymous"),
                "the rejection must name the anonymous bucket so operators can tell "
                        + "it apart from a per-user limit; got: " + secondAnonymous.reason());

        // The exhausted anonymous bucket must NOT spend an authenticated
        // principal's budget — that is the whole point of separating them.
        for (var i = 0; i < 10; i++) {
            assertTrue(gateway.admit("alice", "openai", "gpt-4o").accepted(),
                    "a named principal must be unaffected by anonymous exhaustion");
        }
    }

    @Test
    void anonymousTrafficSharesTheMainLimiterWhenNoDedicatedBucketConfigured() {
        // The three-arg constructor keeps the historical behavior: "anonymous"
        // is just another user id inside the main limiter.
        var gateway = new AiGateway(new PerUserRateLimiter(2, Duration.ofMinutes(5)),
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());

        assertFalse(gateway.hasDedicatedAnonymousLimiter());
        assertTrue(gateway.admit(AiGateway.ANONYMOUS_USER, "openai", "m").accepted());
        assertTrue(gateway.admit(AiGateway.ANONYMOUS_USER, "openai", "m").accepted());
        assertFalse(gateway.admit(AiGateway.ANONYMOUS_USER, "openai", "m").accepted(),
                "without a dedicated bucket anonymous callers exhaust the main limiter");
    }

    @Test
    void frameworkDefaultStaysPermissiveUntilTheOperatorOptsIn() {
        // Correctness Invariant: no enforcement change without opt-in. The
        // holder default must still admit dev-scale traffic.
        for (var i = 0; i < 2_000; i++) {
            assertTrue(AiGatewayHolder.get().admit("alice", "built-in", "m").accepted(),
                    "the default gateway must stay permissive — installing limits "
                            + "is an explicit operator decision");
        }

        AiGatewayHolder.install(GatewayProfiles.production(1, Duration.ofMinutes(5), 1,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop()));

        assertTrue(AiGatewayHolder.get().admit("alice", "built-in", "m").accepted());
        assertFalse(AiGatewayHolder.get().admit("alice", "built-in", "m").accepted(),
                "once installed, the production profile enforces");
    }

    @Test
    void overridesFallBackToProfileDefaultsWhenUnset() {
        var gateway = GatewayProfiles.production(0, null, 0,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());

        assertEquals(GatewayProfiles.PRODUCTION_MAX_REQUESTS_PER_WINDOW,
                gateway.rateLimiter().maxRequests());
        assertEquals(Duration.ofSeconds(GatewayProfiles.PRODUCTION_WINDOW_SECONDS),
                gateway.rateLimiter().window());
    }

    @Test
    void anonymousCeilingNeverDropsBelowOne() {
        // maxRequests=2 / divisor=4 rounds to 0; the floor keeps the bucket usable.
        var gateway = GatewayProfiles.production(2, Duration.ofMinutes(5), 0,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());

        assertEquals(1, gateway.anonymousRateLimiter().maxRequests(),
                "a derived anonymous ceiling must never round down to zero — "
                        + "PerUserRateLimiter rejects maxRequests <= 0");
    }
}
