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
import org.atmosphere.admin.ai.GovernanceController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mode-Parity regression for the Quarkus governance admin endpoints.
 * Mirrors {@code AtmosphereAdminEndpointGovernanceAuthzTest} on the
 * Spring Boot side — same 4-case matrix (flag off → 403, anonymous →
 * 401, authorizer denies → 403, happy path → 200) across the three
 * mutating endpoints: {@code POST /governance/reload},
 * {@code /governance/kill-switch/arm}, {@code /governance/kill-switch/disarm}.
 */
class AdminResourceGovernanceAuthzTest {

    private AtmosphereAdmin admin;
    private GovernanceController governance;
    private AdminResource resource;

    @BeforeEach
    void setUp() {
        admin = Mockito.mock(AtmosphereAdmin.class);
        governance = Mockito.mock(GovernanceController.class);
        Mockito.when(admin.auditLog()).thenReturn(new ControlAuditLog(100));
        Mockito.when(admin.governanceController()).thenReturn(governance);
        Mockito.when(governance.reloadSwappable(Mockito.any(), Mockito.any()))
                .thenReturn(Map.of("swapName", "x"));
        Mockito.when(governance.armKillSwitch(Mockito.any(), Mockito.any()))
                .thenReturn(Map.of("armed", true));
        Mockito.when(governance.disarmKillSwitch())
                .thenReturn(Map.of("armed", false));
        resource = new AdminResource();
        resource.admin = admin;
    }

    // ── Flag-off → 403 across all three mutating endpoints ─────────────────

    @Test
    void reloadReturns403WhenFeatureFlagDisabled() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = false;
        var response = resource.governanceReload(
                securityContextFor("alice"),
                Map.of("swapName", "x", "yaml", "a: b"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void killSwitchArmReturns403WhenFeatureFlagDisabled() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = false;
        var response = resource.governanceKillSwitchArm(
                securityContextFor("alice"),
                Map.of("reason", "drill", "operator", "alice"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void killSwitchDisarmReturns403WhenFeatureFlagDisabled() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = false;
        var response = resource.governanceKillSwitchDisarm(securityContextFor("alice"));
        assertEquals(403, response.getStatus());
    }

    // ── Flag on, anonymous → 401 ───────────────────────────────────────────

    @Test
    void reloadReturns401ForAnonymousCaller() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.governanceReload(
                anonymousSecurityContext(),
                Map.of("swapName", "x", "yaml", "a: b"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void killSwitchArmReturns401ForAnonymousCaller() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.governanceKillSwitchArm(
                anonymousSecurityContext(),
                Map.of("reason", "drill"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void killSwitchDisarmReturns401ForAnonymousCaller() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.governanceKillSwitchDisarm(anonymousSecurityContext());
        assertEquals(401, response.getStatus());
    }

    // ── Flag on, principal, authorizer denies → 403 ────────────────────────

    @Test
    void reloadReturns403WhenAuthorizerDenies() {
        ControlAuthorizer denyAll = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(denyAll);
        resource.writeEnabledOverride = true;
        var response = resource.governanceReload(
                securityContextFor("alice"),
                Map.of("swapName", "x", "yaml", "a: b"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void killSwitchArmReturns403WhenAuthorizerDenies() {
        ControlAuthorizer denyAll = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(denyAll);
        resource.writeEnabledOverride = true;
        var response = resource.governanceKillSwitchArm(
                securityContextFor("alice"),
                Map.of("reason", "drill"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void killSwitchDisarmReturns403WhenAuthorizerDenies() {
        ControlAuthorizer denyAll = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(denyAll);
        resource.writeEnabledOverride = true;
        var response = resource.governanceKillSwitchDisarm(securityContextFor("alice"));
        assertEquals(403, response.getStatus());
    }

    // ── Flag on, principal, REQUIRE_PRINCIPAL → 200 ────────────────────────

    @Test
    void reloadReturns200ForAuthenticatedCaller() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.governanceReload(
                securityContextFor("alice@example.com"),
                Map.of("swapName", "x", "yaml", "a: b"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void killSwitchArmReturns200ForAuthenticatedCaller() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.governanceKillSwitchArm(
                securityContextFor("alice@example.com"),
                Map.of("reason", "drill", "operator", "alice"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void killSwitchDisarmReturns200ForAuthenticatedCaller() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        var response = resource.governanceKillSwitchDisarm(
                securityContextFor("alice@example.com"));
        assertEquals(200, response.getStatus());
    }

    // ── Body parsing safety (match Spring parity) ─────────────────────────

    @Test
    void killSwitchArmRejectsMissingReasonAs400() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        Mockito.when(governance.armKillSwitch(Mockito.isNull(), Mockito.any()))
                .thenThrow(new IllegalArgumentException("reason must not be blank"));
        resource.writeEnabledOverride = true;
        // No "reason" key — must NOT arrive as literal "null" string.
        var response = resource.governanceKillSwitchArm(
                securityContextFor("alice"),
                Map.of("operator", "alice"));
        assertEquals(400, response.getStatus());
    }

    @Test
    void killSwitchArmRejectsNonStringOperatorWithoutClassCast() {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        resource.writeEnabledOverride = true;
        // operator arrives as an Integer — stringField returns null rather
        // than throwing ClassCastException. Controller falls back to the
        // principal name, so the happy path still returns 200.
        var response = resource.governanceKillSwitchArm(
                securityContextFor("alice"),
                Map.of("reason", "drill", "operator", 42));
        assertEquals(200, response.getStatus(),
                "non-string operator must coerce to null, not throw ClassCastException");
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
