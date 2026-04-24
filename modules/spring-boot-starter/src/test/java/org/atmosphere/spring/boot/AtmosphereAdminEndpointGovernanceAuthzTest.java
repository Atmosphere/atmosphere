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
package org.atmosphere.spring.boot;

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.ControlAuthorizer;
import org.atmosphere.admin.ai.GovernanceController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for the governance admin mutating endpoints:
 * {@code POST /governance/reload}, {@code /governance/kill-switch/arm},
 * {@code /governance/kill-switch/disarm}. All three are direct mutations
 * of the governance plane — kill-switch arm halts every AI admission in
 * sub-millisecond time; reload swaps the enforceable content of a live
 * policy. Any request that reaches them MUST pass through
 * {@code guardWrite} — feature flag → Principal → authorizer — just like
 * the existing broadcaster write endpoints.
 *
 * <p>Each endpoint gets the full 4-case matrix:</p>
 * <ul>
 *   <li>Feature flag off → 403</li>
 *   <li>Flag on, no principal → 401</li>
 *   <li>Flag on, principal, authorizer denies → 403</li>
 *   <li>Flag on, principal, {@code REQUIRE_PRINCIPAL} → 200</li>
 * </ul>
 */
class AtmosphereAdminEndpointGovernanceAuthzTest {

    private static final String RELOAD = "/api/admin/governance/reload";
    private static final String ARM = "/api/admin/governance/kill-switch/arm";
    private static final String DISARM = "/api/admin/governance/kill-switch/disarm";

    private AtmosphereAdmin admin;
    private GovernanceController governance;
    private MockMvc mockMvcGateClosed;
    private MockMvc mockMvcGateOpen;

    @BeforeEach
    void setUp() {
        admin = Mockito.mock(AtmosphereAdmin.class);
        governance = Mockito.mock(GovernanceController.class);
        Mockito.when(admin.auditLog()).thenReturn(
                new org.atmosphere.admin.ControlAuditLog(100));
        Mockito.when(admin.governanceController()).thenReturn(governance);
        // Default happy-path stubs — individual tests override only what
        // they exercise. Returning sensible defaults means a missing stub
        // doesn't silently deny (which would mask an authz bypass).
        Mockito.when(governance.reloadSwappable(Mockito.any(), Mockito.any()))
                .thenReturn(Map.of("swapName", "x"));
        Mockito.when(governance.armKillSwitch(Mockito.any(), Mockito.any()))
                .thenReturn(Map.of("armed", true));
        Mockito.when(governance.disarmKillSwitch())
                .thenReturn(Map.of("armed", false));

        mockMvcGateClosed = MockMvcBuilders.standaloneSetup(
                        new AtmosphereAdminEndpoint(admin, envWithWrite(false)))
                .build();
        mockMvcGateOpen = MockMvcBuilders.standaloneSetup(
                        new AtmosphereAdminEndpoint(admin, envWithWrite(true)))
                .build();
    }

    private static org.springframework.core.env.Environment envWithWrite(boolean enabled) {
        var env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("atmosphere.admin.http-write-enabled", String.valueOf(enabled));
        return env;
    }

    // ── feature flag disabled → 403 ─────────────────────────────────────────

    @Test
    void reloadReturns403WhenFeatureFlagDisabled() throws Exception {
        mockMvcGateClosed.perform(post(RELOAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"swapName\":\"x\",\"yaml\":\"a: b\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void killSwitchArmReturns403WhenFeatureFlagDisabled() throws Exception {
        mockMvcGateClosed.perform(post(ARM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"drill\",\"operator\":\"alice\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void killSwitchDisarmReturns403WhenFeatureFlagDisabled() throws Exception {
        mockMvcGateClosed.perform(post(DISARM))
                .andExpect(status().isForbidden());
    }

    // ── flag on, anonymous → 401 ───────────────────────────────────────────

    @Test
    void reloadReturns401ForAnonymousCaller() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        mockMvcGateOpen.perform(post(RELOAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"swapName\":\"x\",\"yaml\":\"a: b\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void killSwitchArmReturns401ForAnonymousCaller() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        mockMvcGateOpen.perform(post(ARM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"drill\",\"operator\":\"alice\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void killSwitchDisarmReturns401ForAnonymousCaller() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        mockMvcGateOpen.perform(post(DISARM))
                .andExpect(status().isUnauthorized());
    }

    // ── flag on, principal, authorizer denies → 403 ────────────────────────

    @Test
    void reloadReturns403WhenAuthorizerDenies() throws Exception {
        ControlAuthorizer denyAll = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(denyAll);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post(RELOAD)
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"swapName\":\"x\",\"yaml\":\"a: b\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void killSwitchArmReturns403WhenAuthorizerDenies() throws Exception {
        ControlAuthorizer denyAll = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(denyAll);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post(ARM)
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"drill\",\"operator\":\"alice\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void killSwitchDisarmReturns403WhenAuthorizerDenies() throws Exception {
        ControlAuthorizer denyAll = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(denyAll);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post(DISARM).principal(alice))
                .andExpect(status().isForbidden());
    }

    // ── flag on, principal, REQUIRE_PRINCIPAL → 200 ────────────────────────

    @Test
    void reloadReturns200ForAuthenticatedCaller() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post(RELOAD)
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"swapName\":\"x\",\"yaml\":\"a: b\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void killSwitchArmReturns200ForAuthenticatedCaller() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post(ARM)
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"drill\",\"operator\":\"alice\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void killSwitchDisarmReturns200ForAuthenticatedCaller() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post(DISARM).principal(alice))
                .andExpect(status().isOk());
    }

    // ── body parsing — missing field must NOT become literal "null" ────────

    @Test
    void killSwitchArmRejectsMissingReasonAs400NotTheStringNull() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        Mockito.when(governance.armKillSwitch(Mockito.isNull(), Mockito.any()))
                .thenThrow(new IllegalArgumentException("reason must not be blank"));
        Principal alice = () -> "alice@example.com";
        // Body with no "reason" key — must NOT arrive as String "null" at
        // the controller method. guardWrite runs first on a null target,
        // then the controller surfaces IllegalArgumentException as 400.
        mockMvcGateOpen.perform(post(ARM)
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"alice\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── /governance/check fallback shape — parity with Quarkus ────────────

    @Test
    void governanceCheckFallbackReturns200AllowPayloadWhenControllerMissing()
            throws Exception {
        // When GovernanceController is not installed, /governance/check must
        // still return the MS-compat 200 allow payload so external gateways
        // routing on {@code allowed} keep working. Shape verified
        // byte-identically against Quarkus via
        // GovernanceController.unconfiguredAllowPayload().
        Mockito.when(admin.governanceController()).thenReturn(null);
        mockMvcGateOpen.perform(post("/api/admin/governance/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent_id\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.allowed").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.decision").value("allow"));
    }

    @Test
    void killSwitchArmCoercesNonStringOperatorToNullWithoutClassCast() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        // operator arrives as an integer — stringField() coerces non-strings
        // to null rather than throwing ClassCastException (which would
        // surface as 500). The controller then falls back to the principal
        // name as the operator stamp, so a valid reason still yields 200.
        // Test name asserts the coercion shape, not the HTTP status.
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post(ARM)
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"drill\",\"operator\":42}"))
                .andExpect(status().isOk());
    }
}
