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

import java.util.Objects;
import java.util.Optional;

/**
 * Outbound facade for every LLM call leaving Atmosphere. Consolidates the
 * existing model router, streaming budget manager, per-user credentials,
 * per-user rate limits, and unified tracing under one named primitive so
 * agent authors see "a gateway" instead of five separate pieces.
 *
 * <h2>What the gateway decides per call</h2>
 *
 * <ol>
 *   <li>Per-user {@link PerUserRateLimiter} consent. A rejected acquire
 *       surfaces to the caller as a backpressure signal — the LLM call
 *       does not dispatch. (Correctness Invariant #3.)</li>
 *   <li>Per-user credential resolution via {@link CredentialResolver}.
 *       Supplies the API key / OAuth token that routes to the LLM
 *       provider. Shared with {@code AgentIdentity} so per-user secrets
 *       flow identically whether the agent is listing facts or calling an
 *       LLM.</li>
 *   <li>Trace emission. Every inbound call records a gateway trace entry
 *       so admin inspection can answer "what went out, when, for whom".</li>
 * </ol>
 *
 * <h2>Composition, not replacement</h2>
 *
 * {@code AiGateway} does not replace {@code DefaultModelRouter},
 * {@code StreamingTextBudgetManager}, or {@code MicrometerAiMetrics} —
 * it orchestrates them under one entry point. Host frameworks (Spring Boot
 * starter, Quarkus extension) construct a gateway at startup and hand the
 * same instance to every agent.
 */
public final class AiGateway {

    /**
     * The principal every unauthenticated caller collapses into.
     * {@code AbstractAgentRuntime.admitThroughGateway} substitutes this id
     * when the execution context carries no {@code userId()}, which means
     * <b>all anonymous traffic shares one rate-limit bucket</b>. Deployments
     * that serve unauthenticated users should size that bucket explicitly via
     * the anonymous-limiter constructor (or the production profile in
     * {@link GatewayProfiles}) rather than letting anonymous traffic compete
     * inside a single per-"user" window.
     */
    public static final String ANONYMOUS_USER = "anonymous";

    private final PerUserRateLimiter rateLimiter;
    private final PerUserRateLimiter anonymousRateLimiter;
    private final CredentialResolver credentialResolver;
    private final GatewayTraceExporter traceExporter;

    public AiGateway(PerUserRateLimiter rateLimiter,
                     CredentialResolver credentialResolver,
                     GatewayTraceExporter traceExporter) {
        this(rateLimiter, null, credentialResolver, traceExporter);
    }

    /**
     * Full constructor with an explicit anonymous-bucket limiter. When
     * {@code anonymousRateLimiter} is {@code null} the {@link #ANONYMOUS_USER}
     * principal is limited by the main {@code rateLimiter} like any other id
     * (the historical behavior); when non-null, anonymous callers draw from
     * the dedicated limiter so the shared-bucket ceiling is an explicit,
     * separately-tunable number instead of an implicit consequence.
     */
    public AiGateway(PerUserRateLimiter rateLimiter,
                     PerUserRateLimiter anonymousRateLimiter,
                     CredentialResolver credentialResolver,
                     GatewayTraceExporter traceExporter) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.anonymousRateLimiter = anonymousRateLimiter;
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.traceExporter = Objects.requireNonNull(traceExporter, "traceExporter");
    }

    /**
     * Admit a call through the gateway. Returns a {@link GatewayDecision} the
     * caller inspects before dispatching the LLM request. This is the single
     * admission point — every agent call goes through here, unconditionally.
     */
    public GatewayDecision admit(String userId, String provider, String model) {
        var anonymous = ANONYMOUS_USER.equals(userId) && anonymousRateLimiter != null;
        var limiter = anonymous ? anonymousRateLimiter : rateLimiter;
        if (!limiter.tryAcquire(userId)) {
            traceExporter.record(new GatewayTraceEntry(userId, provider, model,
                    false, "rate-limited", Optional.empty()));
            return GatewayDecision.rejected(anonymous
                    ? "shared anonymous-bucket rate limit exceeded"
                    : "per-user rate limit exceeded");
        }
        var credentials = credentialResolver.resolve(userId, provider);
        traceExporter.record(new GatewayTraceEntry(userId, provider, model,
                true, "ok", credentials.map(c -> c.identifier())));
        return GatewayDecision.accepted(credentials);
    }

    public PerUserRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * The limiter governing the shared {@link #ANONYMOUS_USER} bucket — the
     * dedicated anonymous limiter when one was configured, otherwise the main
     * per-user limiter (anonymous traffic then shares one window inside it).
     */
    public PerUserRateLimiter anonymousRateLimiter() {
        return anonymousRateLimiter != null ? anonymousRateLimiter : rateLimiter;
    }

    /** Whether a dedicated anonymous-bucket limiter is configured. */
    public boolean hasDedicatedAnonymousLimiter() {
        return anonymousRateLimiter != null;
    }

    public CredentialResolver credentialResolver() {
        return credentialResolver;
    }

    public GatewayTraceExporter traceExporter() {
        return traceExporter;
    }

    /**
     * The outcome of {@link AiGateway#admit(String, String, String)}. A
     * rejected decision carries a human-readable reason; callers MUST honor
     * the reject — the gateway does not throw, it reports.
     */
    public record GatewayDecision(
            boolean accepted,
            String reason,
            Optional<ResolvedCredential> credentials) {

        public static GatewayDecision accepted(Optional<ResolvedCredential> credentials) {
            return new GatewayDecision(true, "ok", credentials);
        }

        public static GatewayDecision rejected(String reason) {
            return new GatewayDecision(false, reason, Optional.empty());
        }
    }

    /**
     * Resolves a per-user credential for an outbound LLM call. Implementations
     * plug into {@code AgentIdentity.CredentialStore} so per-user API keys
     * shared with the identity layer flow into LLM calls.
     */
    @FunctionalInterface
    public interface CredentialResolver {
        /** Resolve a credential for this user against this provider. */
        Optional<ResolvedCredential> resolve(String userId, String provider);

        /** No-op resolver for setups that rely entirely on global config. */
        static CredentialResolver noop() {
            return (u, p) -> Optional.empty();
        }
    }

    /**
     * An opaque, log-safe handle on a credential. The {@link #identifier()} is
     * surfaced to traces (e.g. {@code "key-prefix-ABC..."}) so operators can
     * distinguish which credential was used without leaking the secret itself.
     */
    public record ResolvedCredential(String identifier, String secretRef) {
    }

    /**
     * Sink for one gateway admission event. Implementations route to
     * OpenTelemetry, Micrometer, or a structured audit log.
     */
    @FunctionalInterface
    public interface GatewayTraceExporter {
        void record(GatewayTraceEntry entry);

        /** No-op exporter for tests and embedded setups. */
        static GatewayTraceExporter noop() {
            return entry -> { };
        }
    }

    /** One admission-decision entry captured for tracing. */
    public record GatewayTraceEntry(
            String userId,
            String provider,
            String model,
            boolean accepted,
            String reason,
            Optional<String> credentialIdentifier) {
    }
}
