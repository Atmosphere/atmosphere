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
package org.atmosphere.a2a.security;

import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.a2a.types.APIKeySecurityScheme;
import org.atmosphere.a2a.types.HTTPAuthSecurityScheme;
import org.atmosphere.a2a.types.SecurityRequirement;
import org.atmosphere.a2a.types.SecurityScheme;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Honest-by-default security decoration for the served A2A {@link AgentCard}.
 *
 * <p>The A2A inbound endpoint ships with <strong>no framework-enforced
 * authentication</strong> — {@code message/send} drives the agent (LLM dispatch
 * and tool execution), so a production deployment MUST front it with auth
 * (Spring Security, a servlet filter, or an API gateway). The framework cannot
 * see the deployer's external auth, so it never asserts it. Instead the deployer
 * <em>declares</em> the scheme they enforce (via {@code
 * org.atmosphere.a2a.securityScheme}) and {@link #advertise} relays it onto the
 * served card so A2A clients know what credential to present — a truthful
 * declaration of the contract, not a false claim of framework enforcement
 * (Correctness Invariant #5). When nothing is declared the card stays open (the
 * prior behaviour) and {@link #warnIfUnauthenticated} emits a startup warning so
 * the insecure default cannot ship silently (Correctness Invariant #6).</p>
 */
public final class A2aCardSecurity {

    /** Default header name used for the {@code apiKey} scheme when unset. */
    public static final String DEFAULT_API_KEY_HEADER = "X-API-Key";

    private A2aCardSecurity() {
        // static helpers
    }

    /**
     * Return a copy of {@code card} advertising the deployer-declared security
     * scheme, or {@code card} unchanged when none is declared.
     *
     * @param card         the card to decorate
     * @param scheme       {@code "bearer"} or {@code "apiKey"} (case-insensitive);
     *                     {@code null}/blank/unknown leaves the card open
     * @param apiKeyHeader header name for the {@code apiKey} scheme; falls back to
     *                     {@link #DEFAULT_API_KEY_HEADER} when {@code null}/blank
     */
    public static AgentCard advertise(AgentCard card, String scheme, String apiKeyHeader) {
        if (scheme == null || scheme.isBlank()) {
            return card;
        }
        var normalized = scheme.trim().toLowerCase(Locale.ROOT);
        String name;
        SecurityScheme securityScheme;
        switch (normalized) {
            case "bearer" -> {
                name = "bearer";
                securityScheme = SecurityScheme.httpAuth(new HTTPAuthSecurityScheme(
                        "Bearer token required; enforced by the deployer's gateway/filter, "
                                + "not the Atmosphere framework.", "bearer", null));
            }
            case "apikey" -> {
                name = "apiKey";
                var header = (apiKeyHeader == null || apiKeyHeader.isBlank())
                        ? DEFAULT_API_KEY_HEADER : apiKeyHeader.trim();
                securityScheme = SecurityScheme.apiKey(new APIKeySecurityScheme(
                        "API key required; enforced by the deployer, not the Atmosphere framework.",
                        "header", header));
            }
            default -> {
                // Unknown scheme — do not advertise an unhonored contract.
                return card;
            }
        }
        return card.withSecurity(
                Map.of(name, securityScheme),
                List.of(new SecurityRequirement(Map.of(name, List.of()))));
    }

    /**
     * Whether a startup warning should be emitted: true when no recognised
     * scheme is declared and the warning is not explicitly suppressed.
     */
    public static boolean shouldWarn(String scheme, boolean suppressed) {
        if (suppressed) {
            return false;
        }
        if (scheme == null || scheme.isBlank()) {
            return true;
        }
        var normalized = scheme.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("bearer") && !normalized.equals("apikey");
    }

    /**
     * Emit the unauthenticated-endpoint startup warning through {@code logger}
     * when {@link #shouldWarn} is true. No-op otherwise.
     */
    public static void warnIfUnauthenticated(Logger logger, String scheme, boolean suppressed,
                                             String endpoint, String agentName) {
        if (!shouldWarn(scheme, suppressed)) {
            return;
        }
        logger.warn("A2A endpoint '{}' for agent '{}' is exposed WITHOUT authentication — message/send "
                + "invokes the agent (LLM dispatch + tool execution) for any caller. Front it with auth "
                + "(Spring Security / servlet filter / gateway) and declare "
                + "org.atmosphere.a2a.securityScheme=bearer|apiKey so the served AgentCard is honest. "
                + "See the atmosphere-a2a module README. Suppress with "
                + "org.atmosphere.a2a.suppressAuthWarning=true once auth is enforced out-of-band.",
                endpoint, agentName);
    }
}
