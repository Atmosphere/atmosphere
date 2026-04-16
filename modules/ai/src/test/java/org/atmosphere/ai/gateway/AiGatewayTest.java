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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGatewayTest {

    @Test
    void rateLimiterAcceptsUpToLimitThenRejects() {
        var limiter = new PerUserRateLimiter(3, Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"));
        assertEquals(3, limiter.currentUsage("u1"));
    }

    @Test
    void rateLimiterIsPerUser() {
        var limiter = new PerUserRateLimiter(2, Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u2"));
        assertTrue(limiter.tryAcquire("u2"));
        assertFalse(limiter.tryAcquire("u2"));
    }

    @Test
    void rateLimiterSlidesWindowOverTime() {
        var advancing = new AdvancingClock(Instant.parse("2026-04-15T00:00:00Z"));
        var limiter = new PerUserRateLimiter(2, Duration.ofMillis(500), advancing);

        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"));

        advancing.advance(Duration.ofMillis(600)); // Past window

        assertTrue(limiter.tryAcquire("u1"),
                "past-window entries should expire from the deque");
    }

    @Test
    void rateLimiterRejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new PerUserRateLimiter(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new PerUserRateLimiter(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new PerUserRateLimiter(1, Duration.ofSeconds(-1)));
    }

    @Test
    void gatewayAdmitsCallsWithinRateLimit() {
        var limiter = new PerUserRateLimiter(5, Duration.ofSeconds(10));
        var traces = new ArrayList<AiGateway.GatewayTraceEntry>();
        var gateway = new AiGateway(limiter,
                AiGateway.CredentialResolver.noop(),
                traces::add);

        var decision = gateway.admit("u1", "openai", "gpt-4o");

        assertTrue(decision.accepted());
        assertEquals("ok", decision.reason());
        assertEquals(1, traces.size());
        assertTrue(traces.get(0).accepted());
    }

    @Test
    void gatewayRejectsOverRateLimit() {
        var limiter = new PerUserRateLimiter(2, Duration.ofSeconds(10));
        var traces = new ArrayList<AiGateway.GatewayTraceEntry>();
        var gateway = new AiGateway(limiter,
                AiGateway.CredentialResolver.noop(),
                traces::add);

        gateway.admit("u1", "openai", "gpt-4o");
        gateway.admit("u1", "openai", "gpt-4o");
        var rejected = gateway.admit("u1", "openai", "gpt-4o");

        assertFalse(rejected.accepted());
        assertTrue(rejected.reason().contains("rate limit"));
        assertEquals(3, traces.size());
        assertFalse(traces.get(2).accepted());
        assertEquals("rate-limited", traces.get(2).reason());
    }

    @Test
    void gatewayResolvesCredentials() {
        var limiter = new PerUserRateLimiter(5, Duration.ofSeconds(10));
        var store = Map.of(
                "u1/openai", new AiGateway.ResolvedCredential("key-abc", "ref://secret/u1/openai"),
                "u1/anthropic", new AiGateway.ResolvedCredential("key-def", "ref://secret/u1/anthropic"));
        AiGateway.CredentialResolver resolver =
                (user, provider) -> Optional.ofNullable(store.get(user + "/" + provider));
        var traces = new ArrayList<AiGateway.GatewayTraceEntry>();
        var gateway = new AiGateway(limiter, resolver, traces::add);

        var openaiCall = gateway.admit("u1", "openai", "gpt-4o");
        var anthropicCall = gateway.admit("u1", "anthropic", "claude");
        var unknownProvider = gateway.admit("u1", "mystery", "m1");

        assertEquals("key-abc", openaiCall.credentials().orElseThrow().identifier());
        assertEquals("key-def", anthropicCall.credentials().orElseThrow().identifier());
        assertTrue(unknownProvider.credentials().isEmpty());

        assertEquals(3, traces.size());
        assertEquals(Optional.of("key-abc"), traces.get(0).credentialIdentifier());
        assertEquals(Optional.empty(), traces.get(2).credentialIdentifier());
    }

    @Test
    void noopResolverReturnsEmpty() {
        var resolver = AiGateway.CredentialResolver.noop();
        assertTrue(resolver.resolve("any-user", "any-provider").isEmpty());
    }

    @Test
    void noopTraceExporterDoesNotThrow() {
        var exporter = AiGateway.GatewayTraceExporter.noop();
        exporter.record(new AiGateway.GatewayTraceEntry(
                "u1", "openai", "gpt-4o", true, "ok", Optional.empty()));
    }

    @Test
    void traceExporterSeesEveryAdmission() {
        var limiter = new PerUserRateLimiter(100, Duration.ofSeconds(1));
        List<AiGateway.GatewayTraceEntry> log = new ArrayList<>();
        var gateway = new AiGateway(limiter, AiGateway.CredentialResolver.noop(), log::add);

        for (var i = 0; i < 10; i++) {
            gateway.admit("u" + i, "openai", "gpt-4o");
        }

        assertEquals(10, log.size());
        for (var entry : log) {
            assertTrue(entry.accepted());
        }
    }

    @Test
    void gatewayRejectsNullDependencies() {
        var limiter = new PerUserRateLimiter(1, Duration.ofSeconds(1));
        assertThrows(NullPointerException.class,
                () -> new AiGateway(null, AiGateway.CredentialResolver.noop(),
                        AiGateway.GatewayTraceExporter.noop()));
        assertThrows(NullPointerException.class,
                () -> new AiGateway(limiter, null, AiGateway.GatewayTraceExporter.noop()));
        assertThrows(NullPointerException.class,
                () -> new AiGateway(limiter, AiGateway.CredentialResolver.noop(), null));
    }

    // Helpers --------------------------------------------------------------

    private static final class AdvancingClock extends Clock {
        private final AtomicLong now;

        AdvancingClock(Instant start) {
            this.now = new AtomicLong(start.toEpochMilli());
        }

        void advance(Duration d) {
            now.addAndGet(d.toMillis());
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }
}
