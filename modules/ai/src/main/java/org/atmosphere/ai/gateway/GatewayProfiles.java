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

import java.time.Duration;

/**
 * Named {@link AiGateway} configurations. The framework default stays the
 * permissive dev/test gateway ({@link AiGatewayHolder}: one million calls per
 * hour, effectively unrestricted) — no enforcement is forced on anyone. The
 * {@code production} profile here is the documented one-line opt-in that
 * installs meaningful limits:
 *
 * <pre>
 *   atmosphere.ai.gateway.profile=production          # Spring Boot
 *   quarkus.atmosphere.ai.gateway.profile=production  # Quarkus
 *   AiGatewayHolder.install(GatewayProfiles.production());  // programmatic
 * </pre>
 *
 * <h2>Why these numbers</h2>
 *
 * {@link PerUserRateLimiter} is a sliding window of request timestamps: the
 * worst-case memory per tracked principal is {@code maxRequests} entries, and
 * an idle principal is evicted one {@code window} after its last call.
 *
 * <ul>
 *   <li><b>{@value #PRODUCTION_MAX_REQUESTS_PER_WINDOW} requests per
 *       {@value #PRODUCTION_WINDOW_SECONDS}-second window per principal</b>
 *       (1 request/second sustained, bursts absorbed inside the window).
 *       Interactive agent traffic is human-paced — a few LLM dispatches per
 *       user turn — so legitimate users sit far below this ceiling, while a
 *       runaway tool loop or scripted abuse hits it within minutes. The
 *       5-minute window also keeps the limiter's documented
 *       {@link PerUserRateLimiter#DEFAULT_MAX_TRACKED_USERS 100k tracked-user}
 *       worst case bounded (≤ 300 timestamps per principal) and evicts idle
 *       principals quickly.</li>
 *   <li><b>Anonymous divisor {@value #PRODUCTION_ANONYMOUS_DIVISOR}</b> —
 *       every unauthenticated caller collapses into the single shared
 *       {@link AiGateway#ANONYMOUS_USER} bucket, so its ceiling is a
 *       whole-deployment cap, not a per-person one. The production profile
 *       gives that shared bucket its own limiter at {@code maxRequests /
 *       divisor} (default {@value #PRODUCTION_MAX_REQUESTS_PER_WINDOW} / 4 =
 *       75 per window): unattributable traffic is penalized relative to
 *       authenticated principals instead of silently enjoying the same
 *       budget.</li>
 * </ul>
 */
public final class GatewayProfiles {

    /** Config key selecting the gateway profile (Spring: {@code atmosphere.ai.gateway.profile}). */
    public static final String PROFILE_PROPERTY = "atmosphere.ai.gateway.profile";

    /** Value of {@link #PROFILE_PROPERTY} that installs the production gateway. */
    public static final String PRODUCTION = "production";

    /** Per-principal request ceiling inside one production window. */
    public static final int PRODUCTION_MAX_REQUESTS_PER_WINDOW = 300;

    /** Production sliding-window length in seconds (5 minutes). */
    public static final int PRODUCTION_WINDOW_SECONDS = 300;

    /**
     * Divisor applied to {@code maxRequests} to size the shared anonymous
     * bucket when no explicit anonymous limit is configured.
     */
    public static final int PRODUCTION_ANONYMOUS_DIVISOR = 4;

    private GatewayProfiles() {
        // static factory
    }

    /** Production gateway with the documented defaults and no-op credential/trace hooks. */
    public static AiGateway production() {
        return production(0, null, 0,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());
    }

    /**
     * Production gateway with operator overrides. Any zero/null argument falls
     * back to the profile default so config layers only pass what the operator
     * actually set.
     *
     * @param maxRequestsPerWindow per-principal ceiling; {@code <= 0} uses
     *                             {@value #PRODUCTION_MAX_REQUESTS_PER_WINDOW}
     * @param window               sliding-window length; {@code null} uses
     *                             {@value #PRODUCTION_WINDOW_SECONDS} seconds
     * @param anonymousMaxRequests ceiling for the shared anonymous bucket;
     *                             {@code <= 0} derives {@code maxRequests /
     *                             } {@value #PRODUCTION_ANONYMOUS_DIVISOR}
     *                             (minimum 1)
     * @param credentialResolver   per-user credential hook
     * @param traceExporter        admission-trace sink
     */
    public static AiGateway production(int maxRequestsPerWindow,
                                       Duration window,
                                       int anonymousMaxRequests,
                                       AiGateway.CredentialResolver credentialResolver,
                                       AiGateway.GatewayTraceExporter traceExporter) {
        var effectiveMax = maxRequestsPerWindow > 0
                ? maxRequestsPerWindow : PRODUCTION_MAX_REQUESTS_PER_WINDOW;
        var effectiveWindow = window != null && !window.isNegative() && !window.isZero()
                ? window : Duration.ofSeconds(PRODUCTION_WINDOW_SECONDS);
        var effectiveAnonymous = anonymousMaxRequests > 0
                ? anonymousMaxRequests
                : Math.max(1, effectiveMax / PRODUCTION_ANONYMOUS_DIVISOR);
        return new AiGateway(
                new PerUserRateLimiter(effectiveMax, effectiveWindow),
                new PerUserRateLimiter(effectiveAnonymous, effectiveWindow),
                credentialResolver,
                traceExporter);
    }
}
