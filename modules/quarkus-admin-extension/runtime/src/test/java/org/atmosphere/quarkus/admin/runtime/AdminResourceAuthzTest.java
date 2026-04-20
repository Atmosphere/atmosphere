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
        resource.writeEnabled = false;
        var response = resource.broadcast(securityContextFor("alice"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void writeReturns401ForAnonymousCallerWhenFlagEnabled() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabled = true;
        // Anonymous: SecurityContext resolves no Principal, attribute-based
        // fallbacks empty — guardWrite must return 401.
        var response = resource.broadcast(anonymousSecurityContext(),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void writeReturns200ForAuthenticatedCallerUnderRequirePrincipal() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabled = true;
        var response = resource.broadcast(securityContextFor("alice@example.com"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void customAuthorizerThatDeniesReturns403() {
        ControlAuthorizer roleCheck = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(roleCheck);
        resource.writeEnabled = true;
        var response = resource.broadcast(securityContextFor("alice"),
                Map.of("broadcasterId", "/chat", "message", "x"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void denyAllAuthorizerBlocksEvenAuthenticatedCallers() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.DENY_ALL);
        resource.writeEnabled = true;
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
        resource.writeEnabled = true;

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
        resource.writeEnabled = true;

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

    private static SecurityContext securityContextFor(String name) {
        var ctx = Mockito.mock(SecurityContext.class);
        Principal p = () -> name;
        Mockito.when(ctx.getUserPrincipal()).thenReturn(p);
        return ctx;
    }

    private static SecurityContext anonymousSecurityContext() {
        var ctx = Mockito.mock(SecurityContext.class);
        Mockito.when(ctx.getUserPrincipal()).thenReturn(null);
        return ctx;
    }
}
