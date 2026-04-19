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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for P0: admin HTTP write endpoints consulted only the
 * {@code atmosphere.admin.http-write-enabled} feature flag. Once the flag
 * was flipped, every mutating POST/DELETE was open to anonymous callers —
 * direct violation of Correctness Invariant #6 (authn + authz on every
 * mutating surface).
 *
 * <p>Fixed by {@code guardWrite(HttpServletRequest, action, target)} which
 * now enforces (in order): feature flag → servlet Principal → installed
 * {@link ControlAuthorizer}. Every decision is recorded in the control
 * audit log.</p>
 */
class AtmosphereAdminEndpointAuthzTest {

    private AtmosphereAdmin admin;
    private MockMvc mockMvcGateClosed;
    private MockMvc mockMvcGateOpen;

    @BeforeEach
    void setUp() {
        admin = Mockito.mock(AtmosphereAdmin.class, Mockito.RETURNS_DEEP_STUBS);
        // Audit log is a concrete class — back the mock chain with a real
        // instance so the endpoint's record() calls don't NPE.
        Mockito.when(admin.auditLog()).thenReturn(
                new org.atmosphere.admin.ControlAuditLog(100));
        Mockito.when(admin.framework().broadcast(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(true);
        // Gate-closed instance: writeEnabled=false — every write returns 403
        // from the feature-flag layer before even reaching authn/authz.
        mockMvcGateClosed = MockMvcBuilders.standaloneSetup(
                        new AtmosphereAdminEndpoint(admin, false))
                .build();
        // Gate-open instance: writeEnabled=true — authn and authz now
        // matter and are asserted by the tests below.
        mockMvcGateOpen = MockMvcBuilders.standaloneSetup(
                        new AtmosphereAdminEndpoint(admin, true))
                .build();
    }

    @Test
    void writeEndpointsReturn403WhenFeatureFlagDisabled() throws Exception {
        mockMvcGateClosed.perform(post("/api/admin/broadcasters/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broadcasterId\":\"/chat\",\"message\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeEndpointsReturn401ForAnonymousCallerWhenFlagEnabled() throws Exception {
        // Principal == null on a plain MockMvc request.
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        mockMvcGateOpen.perform(post("/api/admin/broadcasters/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broadcasterId\":\"/chat\",\"message\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void writeEndpointsReturn200ForAuthenticatedCallerUnderRequirePrincipal()
            throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.REQUIRE_PRINCIPAL);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post("/api/admin/broadcasters/broadcast")
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broadcasterId\":\"/chat\",\"message\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void customAuthorizerThatDeniesReturns403ForAuthenticatedCaller()
            throws Exception {
        ControlAuthorizer roleCheck = (action, target, principal) -> false;
        Mockito.when(admin.authorizer()).thenReturn(roleCheck);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post("/api/admin/broadcasters/broadcast")
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broadcasterId\":\"/chat\",\"message\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void denyAllAuthorizerBlocksEvenAuthenticatedCallers() throws Exception {
        Mockito.when(admin.authorizer()).thenReturn(ControlAuthorizer.DENY_ALL);
        Principal alice = () -> "alice@example.com";
        mockMvcGateOpen.perform(post("/api/admin/broadcasters/broadcast")
                        .principal(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broadcasterId\":\"/chat\",\"message\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
