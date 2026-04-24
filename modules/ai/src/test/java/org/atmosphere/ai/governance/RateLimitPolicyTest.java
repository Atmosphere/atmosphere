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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitPolicyTest {

    private static AiRequest userReq(String userId) {
        return new AiRequest("msg", null, null, userId, null, null, null, null, null);
    }

    private static AiRequest sessionReq(String sessionId) {
        return new AiRequest("msg", null, null, null, sessionId, null, null, null, null);
    }

    private static AiRequest anonReq() {
        return new AiRequest("msg", null, null, null, null, null, null, null, null);
    }

    private static PolicyContext preAdm(AiRequest r) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION, r, "");
    }

    @Test
    void admitsUntilLimitThenDenies() {
        var policy = new RateLimitPolicy("rl", 3, Duration.ofSeconds(60));
        var ctx = preAdm(userReq("alice"));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx));
        var denied = policy.evaluate(ctx);
        var deny = assertInstanceOf(PolicyDecision.Deny.class, denied);
        assertTrue(deny.reason().contains("rate-limited"));
        assertTrue(deny.reason().contains("user:alice"));
    }

    @Test
    void separateSubjectsCountedIndependently() {
        var policy = new RateLimitPolicy("rl", 2, Duration.ofSeconds(60));
        var alice = preAdm(userReq("alice"));
        var bob = preAdm(userReq("bob"));

        policy.evaluate(alice);
        policy.evaluate(alice);
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(alice));

        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(bob),
                "bob's counter is separate from alice's");
    }

    @Test
    void sessionIdFallbackWhenNoUserId() {
        var policy = new RateLimitPolicy("rl", 1, Duration.ofSeconds(60));
        var ctx = preAdm(sessionReq("sess-42"));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(ctx));
        assertTrue(deny.reason().contains("session:sess-42"));
    }

    @Test
    void anonymousFallbackWhenNoIdentity() {
        var policy = new RateLimitPolicy("rl", 1, Duration.ofSeconds(60));
        var ctx = preAdm(anonReq());
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(ctx));
        assertTrue(deny.reason().contains("anonymous"));
    }

    @Test
    void windowSlideAllowsReadmissionAfterExpiry() {
        var clockRef = new AtomicReference<>(Instant.parse("2026-04-23T12:00:00Z"));
        var adjustable = new Clock() {
            @Override public Instant instant() { return clockRef.get(); }
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        };
        var policy = new RateLimitPolicy("rl", "code:test", "1",
                2, Duration.ofSeconds(60), adjustable, r -> "alice");
        var ctx = preAdm(userReq("alice"));

        policy.evaluate(ctx);
        policy.evaluate(ctx);
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(ctx),
                "at T+0 two hits land, third is denied");

        // Advance past the window; old timestamps prune.
        clockRef.set(clockRef.get().plusSeconds(61));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(ctx),
                "sliding window: after the window passes, the counter resets");
    }

    @Test
    void postResponsePhaseAlwaysAdmits() {
        var policy = new RateLimitPolicy("rl", 1, Duration.ofSeconds(60));
        var preCtx = preAdm(userReq("alice"));
        policy.evaluate(preCtx);
        var postCtx = new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                userReq("alice"), "response text");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(postCtx),
                "rate limiting is a pre-admission concern");
    }

    @Test
    void customSubjectExtractorIsUsed() {
        var policy = new RateLimitPolicy("rl", "code:test", "1",
                1, Duration.ofSeconds(60),
                Clock.systemUTC(),
                req -> "tenant:" + (req.metadata() == null ? "" : req.metadata().get("tenant")));

        var tenantA = preAdm(new AiRequest("msg", null, null, null, null, null, null,
                java.util.Map.of("tenant", "acme"), null));
        var tenantB = preAdm(new AiRequest("msg", null, null, null, null, null, null,
                java.util.Map.of("tenant", "initech"), null));

        policy.evaluate(tenantA);
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(tenantA));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(tenantB),
                "custom extractor keyed by tenant — tenant B is untouched");
    }

    @Test
    void currentHitsExposesPerSubjectCount() {
        var policy = new RateLimitPolicy("rl", 5, Duration.ofSeconds(60));
        var ctx = preAdm(userReq("alice"));
        policy.evaluate(ctx);
        policy.evaluate(ctx);
        assertEquals(2, policy.currentHits("user:alice"));
        assertEquals(0, policy.currentHits("user:bob"));
    }

    @Test
    void resetClearsAllCounters() {
        var policy = new RateLimitPolicy("rl", 1, Duration.ofSeconds(60));
        policy.evaluate(preAdm(userReq("alice")));
        policy.reset();
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(preAdm(userReq("alice"))));
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitPolicy("rl", 0, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitPolicy("rl", 5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitPolicy("rl", 5, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitPolicy("", 5, Duration.ofSeconds(60)));
    }
}
