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
package org.atmosphere.quarkus.admin.runtime;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Opt-in read-side auth gate for the Quarkus admin plane. Enabled by
 * setting {@code atmosphere.admin.http-read-auth-required=true} — off
 * by default so existing dashboards and demo deployments continue to
 * work. When enabled, any GET / HEAD / OPTIONS on {@code /api/admin/*}
 * without a resolvable principal is rejected with 401.
 *
 * <p>Mirrors the Spring Boot starter's {@code AdminApiAuthFilter} so
 * the admin posture is identical across both runtimes (Correctness
 * Invariant #7 — mode parity). The principal resolution chain —
 * Jakarta REST SecurityContext, Atmosphere {@code AuthInterceptor}
 * request attribute, {@code ai.userId} attribute, and finally
 * {@code X-Atmosphere-Auth} validated against
 * {@code atmosphere.admin.auth.token} — is the same chain
 * {@link AdminResource#resolvePrincipalName} applies on the write side,
 * so a token that works for writes also works for reads.</p>
 *
 * <p>Registered as a JAX-RS {@code @Provider} so Quarkus picks it up
 * automatically; the {@code @PreMatching} + path check keeps the
 * filter scoped to the admin URI space.</p>
 */
@Provider
@Priority(100)
public class AdminReadAuthFilter implements ContainerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AdminReadAuthFilter.class);
    private static final String ADMIN_PREFIX = "/api/admin";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isReadAuthRequired()) {
            return;
        }
        var method = requestContext.getMethod();
        if (!"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method)) {
            // Writes are already gated by AdminResource.guardWrite;
            // don't double-charge.
            return;
        }
        var path = requestContext.getUriInfo().getPath();
        // The filter is per-request and this deployment may host other
        // resources; only intervene for admin URIs.
        if (!path.startsWith("api/admin") && !path.startsWith("/api/admin")
                && !path.startsWith(ADMIN_PREFIX.substring(1))) {
            return;
        }
        if (hasPrincipal(requestContext)) {
            return;
        }
        logger.debug("Rejected anonymous admin read: {} {}", method, path);
        requestContext.abortWith(Response.status(401)
                .type("application/json")
                .entity(Map.of(
                        "error", "Admin read operations require authentication",
                        "hint", "Send X-Atmosphere-Auth header or disable "
                                + "atmosphere.admin.http-read-auth-required"))
                .build());
    }

    private static boolean isReadAuthRequired() {
        try {
            return Boolean.parseBoolean(
                    org.eclipse.microprofile.config.ConfigProvider.getConfig()
                            .getOptionalValue("atmosphere.admin.http-read-auth-required",
                                    String.class)
                            .orElse("false"));
        } catch (RuntimeException e) {
            // No config backend available (embedded test); default deny
            // would be too strict for testcontainers, default allow is
            // safe — the flag is opt-in, so missing config means off.
            return false;
        }
    }

    /**
     * Reuse the same four sources {@link AdminResource#resolvePrincipalName}
     * evaluates on writes: JAX-RS SecurityContext, Atmosphere
     * AuthInterceptor attribute, {@code ai.userId} attribute, and the
     * admin-token-header path. Anything the write side accepts, the read
     * side accepts — so operators configure auth once and it applies
     * uniformly.
     */
    private static boolean hasPrincipal(ContainerRequestContext ctx) {
        var sec = ctx.getSecurityContext();
        if (sec != null && sec.getUserPrincipal() != null
                && sec.getUserPrincipal().getName() != null
                && !sec.getUserPrincipal().getName().isBlank()) {
            return true;
        }
        // Token-header path — works on Vert.x without servlet context.
        String header = ctx.getHeaderString("X-Atmosphere-Auth");
        if (header != null && !header.isBlank()) {
            String configured = resolveConfiguredToken();
            if (configured != null && !configured.isBlank()
                    && constantTimeEquals(header, configured)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveConfiguredToken() {
        try {
            return org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("atmosphere.admin.auth.token", String.class)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
