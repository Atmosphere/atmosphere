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

import jakarta.ws.rs.core.SecurityContext;
import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.ControlAuditLog;
import org.atmosphere.admin.ControlAuthorizer;
import org.atmosphere.admin.framework.FrameworkController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the Quarkus admin-write triple-gate. Mirrors the
 * Spring Boot starter's {@code AtmosphereAdminEndpointAuthzTest} so a
 * future CDI refactor that drops {@code @Context SecurityContext} or
 * downgrades the authorizer lookup silently regresses to the prior
 * anonymous-write bypass and THIS test catches it — not production.
 *
 * <p>Drives {@code AdminResource} directly (no full Quarkus boot) —
 * Jakarta REST resources are POJOs once you mock {@code SecurityContext}
 * and construct the resource in-JVM. Faster than
 * {@code QuarkusIntegrationTest} and adequate for gate coverage.</p>
 */
class AdminResourceAuthzTest {

    private AtmosphereAdmin admin;
    private AdminResource resource;

    @BeforeEach
    void setUp() {
        // Explicit stubs (no RETURNS_DEEP_STUBS) so the test surface is
        // visible from the setup block — adding a new call site to
        // AdminResource then fails fast with a clear NPE rather than
        // producing silently-correct deep-stub chains.
        admin = Mockito.mock(AtmosphereAdmin.class);
        var framework = Mockito.mock(FrameworkController.class);
        Mockito.when(admin.auditLog()).thenReturn(new ControlAuditLog(100));
        Mockito.when(admin.framework()).thenReturn(framework);
        Mockito.when(framework.broadcast(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(true);
        resource = new AdminResource();
        resource.admin = admin;
    }

    @Test
    void writeReturns403WhenFeatureFlagDisabled() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = false;
        var response = resource.broadcast(securityContextFor("alice"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void writeReturns401ForAnonymousCallerWhenFlagEnabled() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        // Anonymous: SecurityContext resolves no Principal, attribute-based
        // fallbacks empty — guardWrite must return 401.
        var response = resource.broadcast(anonymousSecurityContext(),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void writeReturns200ForAuthenticatedCallerUnderRequirePrincipal() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.broadcast(securityContextFor("alice@example.com"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void customAuthorizerThatDeniesReturns403() {
        ControlAuthorizer roleCheck = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(roleCheck);
        resource.writeEnabledOverride = true;
        var response = resource.broadcast(securityContextFor("alice"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void denyAllAuthorizerBlocksEvenAuthenticatedCallers() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.DENY_ALL);
        resource.writeEnabledOverride = true;
        var response = resource.broadcast(securityContextFor("alice"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(403, response.getStatus());
    }

    /**
     * Regression for the v0.9 review's "principal resolution differs
     * from Spring" finding. Quarkus must admit a caller whose principal
     * only arrives via the Atmosphere {@code AuthInterceptor} attribute
     * (token-validated), NOT via Jakarta Security. Parity test across
     * the two starters — Correctness Invariant #7.
     */
    @Test
    void principalFromAtmosphereAuthInterceptorAttributeIsRespected() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;

        Principal tokenPrincipal = () -> "demo-user";
        resource.servletRequest = Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        Mockito.when(resource.servletRequest.getAttribute("org.atmosphere.auth.principal"))
                .thenReturn(tokenPrincipal);

        var response = resource.broadcast(anonymousSecurityContext(),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(200, response.getStatus(),
                "Quarkus must accept an Atmosphere-AuthInterceptor-set Principal for "
                        + "parity with the Spring starter (mode parity)");
    }

    /**
     * Third source in the principal chain — {@code ai.userId} request
     * attribute populated by the AI pipeline (framework-scoped
     * identity, not Jakarta Security or Atmosphere AuthInterceptor).
     * Spring's {@code guardWrite} admits it; Quarkus must too.
     */
    @Test
    void principalFromAiUserIdAttributeIsRespected() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;

        resource.servletRequest = Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        Mockito.when(resource.servletRequest.getAttribute("org.atmosphere.auth.principal"))
                .thenReturn(null);
        Mockito.when(resource.servletRequest.getAttribute("ai.userId"))
                .thenReturn("pipeline-resolved-user");

        var response = resource.broadcast(anonymousSecurityContext(),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(200, response.getStatus(),
                "Quarkus must accept the ai.userId request attribute as principal — "
                        + "third source in the resolution chain, parity with Spring");
    }

    /**
     * Fourth source in the principal chain — {@code X-Atmosphere-Auth}
     * header validated against {@code atmosphere.admin.auth.token}.
     * This is the JAX-RS-specific path: the Quarkus admin surface is a
     * raw JAX-RS resource (not Atmosphere-handled), so Atmosphere's
     * {@code AuthInterceptor} doesn't fire to populate the request
     * attribute. Without this last resort, operators would have to
     * stand up Quarkus Security even for the demo-token use case the
     * Spring sample supports out-of-box.
     */
    @Test
    void principalFromXAtmosphereAuthHeaderAgainstConfiguredTokenIsRespected() {
        System.setProperty("atmosphere.admin.auth.token", "demo-token");
        try {
            Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
            resource.writeEnabledOverride = true;

            // Header injection is via Jakarta REST HttpHeaders so the
            // path works on both Undertow (servlet) and resteasy-reactive
            // (Vert.x) — resteasy-reactive throws UT000048 on servlet
            // request access, so the servlet path is a non-starter.
            resource.jaxrsHeaders = Mockito.mock(jakarta.ws.rs.core.HttpHeaders.class);
            Mockito.when(resource.jaxrsHeaders.getHeaderString("X-Atmosphere-Auth"))
                    .thenReturn("demo-token");

            var response = resource.broadcast(anonymousSecurityContext(),
                    Map.of("broadcasterId", "/chat", "message", "x"));
            assertEquals(200, response.getStatus(),
                    "Quarkus must accept X-Atmosphere-Auth validated against the "
                            + "configured admin token — fourth source in the chain.");
        } finally {
            System.clearProperty("atmosphere.admin.auth.token");
        }
    }

    /**
     * Fourth-source negative: wrong token must be rejected the same as
     * anonymous. Regression for the constant-time-compare path — a
     * length-mismatch short-circuit is fine, but a prefix-match early
     * exit would be a timing leak worth catching.
     */
    @Test
    void wrongXAtmosphereAuthTokenIsRejected() {
        System.setProperty("atmosphere.admin.auth.token", "demo-token");
        try {
            Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
            resource.writeEnabledOverride = true;

            resource.jaxrsHeaders = Mockito.mock(jakarta.ws.rs.core.HttpHeaders.class);
            Mockito.when(resource.jaxrsHeaders.getHeaderString("X-Atmosphere-Auth"))
                    .thenReturn("wrong-token");

            var response = resource.broadcast(anonymousSecurityContext(),
                    Map.of("broadcasterId", "/chat", "message", "x"));
            assertEquals(401, response.getStatus(),
                    "Mismatched admin token must return 401 — the token source must "
                            + "not bypass the principal requirement.");
        } finally {
            System.clearProperty("atmosphere.admin.auth.token");
        }
    }

    /**
     * Regression for the Vert.x-thread scenario: resteasy-reactive
     * dispatches on Vert.x, not Undertow, so
     * {@code servletRequest.getAttribute(...)} throws
     * {@code IllegalStateException: UT000048 No request is currently
     * active}. {@link AdminResource#resolvePrincipalName} must swallow
     * that and fall through to the token-header path — otherwise every
     * admin write on Quarkus becomes a 500.
     */
    @Test
    void servletRequestThrowingUT000048FallsThroughToTokenHeader() {
        System.setProperty("atmosphere.admin.auth.token", "demo-token");
        try {
            Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
            resource.writeEnabledOverride = true;

            // Simulate the resteasy-reactive Vert.x-thread failure:
            // servletRequest proxy is non-null, but every access throws.
            resource.servletRequest = Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
            Mockito.when(resource.servletRequest.getAttribute(Mockito.anyString()))
                    .thenThrow(new IllegalStateException("UT000048: No request is currently active"));

            // Token-header path (Jakarta REST, thread-agnostic) carries
            // the admission decision.
            resource.jaxrsHeaders = Mockito.mock(jakarta.ws.rs.core.HttpHeaders.class);
            Mockito.when(resource.jaxrsHeaders.getHeaderString("X-Atmosphere-Auth"))
                    .thenReturn("demo-token");

            var response = resource.broadcast(anonymousSecurityContext(),
                    Map.of("broadcasterId", "/chat", "message", "x"));
            assertEquals(200, response.getStatus(),
                    "Vert.x-thread UT000048 from servletRequest must not propagate; "
                            + "the token-header path must still admit the caller.");
        } finally {
            System.clearProperty("atmosphere.admin.auth.token");
        }
    }

    // ── Opt-in role authorization (atmosphere.admin.required-role) ──
    // Proves the MicroProfile-JWT path: quarkus-smallrye-jwt maps the JWT
    // `groups` claim onto SecurityContext roles, so a JWT carrying the
    // configured role is admitted and one without it is rejected. The token
    // path is deliberately left ungated. Parity with the Spring starter's
    // AtmosphereAdminEndpointAuthzTest (Correctness Invariant #7).

    /**
     * A JWT whose {@code groups} claim includes the configured role
     * (surfaced by Quarkus as {@code SecurityContext.isUserInRole}) is
     * admitted for writes.
     */
    @Test
    void writeAllowedWhenJakartaPrincipalCarriesRequiredRole() {
        System.setProperty("atmosphere.admin.required-role", "atmosphere-admin");
        try {
            Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
            resource.writeEnabledOverride = true;
            var response = resource.broadcast(
                    securityContextWithRole("alice@example.com", "atmosphere-admin"),
                    Map.of("broadcasterId", "/chat", "message", "x"));
            assertEquals(200, response.getStatus(),
                    "a JWT principal carrying the required role must be admitted");
        } finally {
            System.clearProperty("atmosphere.admin.required-role");
        }
    }

    /**
     * A Jakarta-Security principal that does NOT carry the configured
     * role is rejected with 403 even though the feature flag is on and a
     * principal is present — the role gate fires before the
     * ControlAuthorizer.
     */
    @Test
    void writeRejectedWhenJakartaPrincipalLacksRequiredRole() {
        System.setProperty("atmosphere.admin.required-role", "atmosphere-admin");
        try {
            Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
            resource.writeEnabledOverride = true;
            // securityContextFor stubs no roles → isUserInRole(...) is false.
            var response = resource.broadcast(securityContextFor("mallory@example.com"),
                    Map.of("broadcasterId", "/chat", "message", "x"));
            assertEquals(403, response.getStatus(),
                    "a JWT principal lacking the required role must be rejected with 403");
        } finally {
            System.clearProperty("atmosphere.admin.required-role");
        }
    }

    /**
     * The role gate is scoped to container-security (JWT) principals: the
     * X-Atmosphere-Auth operator token path has no roles and must remain
     * admissible (governed by ControlAuthorizer) even when a role is
     * required — otherwise enabling the flag would silently break
     * token-based operator tooling.
     */
    @Test
    void requiredRoleDoesNotGateTheAdminTokenPath() {
        System.setProperty("atmosphere.admin.required-role", "atmosphere-admin");
        System.setProperty("atmosphere.admin.auth.token", "demo-token");
        try {
            Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
            resource.writeEnabledOverride = true;
            resource.jaxrsHeaders = Mockito.mock(jakarta.ws.rs.core.HttpHeaders.class);
            Mockito.when(resource.jaxrsHeaders.getHeaderString("X-Atmosphere-Auth"))
                    .thenReturn("demo-token");
            var response = resource.broadcast(anonymousSecurityContext(),
                    Map.of("broadcasterId", "/chat", "message", "x"));
            assertEquals(200, response.getStatus(),
                    "the role gate constrains JWT principals only; the X-Atmosphere-Auth "
                            + "operator token stays governed by ControlAuthorizer");
        } finally {
            System.clearProperty("atmosphere.admin.required-role");
            System.clearProperty("atmosphere.admin.auth.token");
        }
    }

    /**
     * Default posture: with no required role configured a Jakarta
     * principal that carries no roles still writes — proving the gate is
     * strictly opt-in and does not regress the existing principal-only
     * posture.
     */
    @Test
    void noRequiredRoleConfigured_jakartaPrincipalWithoutRolesStillWrites() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.broadcast(securityContextFor("alice@example.com"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(200, response.getStatus(),
                "with atmosphere.admin.required-role unset the role gate must not fire");
    }

    private static SecurityContext securityContextFor(String name) {
        var ctx = Mockito.mock(SecurityContext.class);
        Principal p = () -> name;
        Mockito.when(ctx.getUserPrincipal()).thenReturn(p);
        return ctx;
    }

    private static SecurityContext securityContextWithRole(String name, String role) {
        var ctx = securityContextFor(name);
        Mockito.when(ctx.isUserInRole(role)).thenReturn(true);
        return ctx;
    }

    private static SecurityContext anonymousSecurityContext() {
        var ctx = Mockito.mock(SecurityContext.class);
        Mockito.when(ctx.getUserPrincipal()).thenReturn(null);
        return ctx;
    }
}
