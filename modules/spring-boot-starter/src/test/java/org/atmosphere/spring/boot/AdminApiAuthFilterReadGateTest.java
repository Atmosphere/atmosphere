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

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.atmosphere.auth.TokenValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the opt-in admin-read auth gate
 * ({@code atmosphere.admin.http-read-auth-required}). Closes the P1
 * finding that admin read endpoints (overview, journal, flow) were
 * unauthenticated by default — operators of multi-tenant or
 * untrusted-network deployments flip the flag and reads require the
 * same token chain writes do.
 */
class AdminApiAuthFilterReadGateTest {

    @Test
    void readAuthDisabledByDefault_anonymousGetPassesThrough() throws Exception {
        var env = new MockEnvironment();
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorRejectingEverything(), env);
        var req = new MockHttpServletRequest("GET", "/api/admin/overview");
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus(),
                "default posture unchanged — anonymous GET must pass to the "
                + "endpoint so existing demo consoles keep working");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void readAuthEnabled_anonymousGetReturns401() throws Exception {
        var env = new MockEnvironment()
                .withProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorRejectingEverything(), env);
        var req = new MockHttpServletRequest("GET", "/api/admin/overview");
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertEquals(401, res.getStatus(),
                "anonymous GET on admin plane must be rejected when the "
                + "read-auth flag is enabled — no leak of operational data");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void readAuthEnabled_authenticatedGetPassesThrough() throws Exception {
        var env = new MockEnvironment()
                .withProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorAccepting("demo-token", "demo-user"), env);
        var req = new MockHttpServletRequest("GET", "/api/admin/overview");
        req.addHeader("X-Atmosphere-Auth", "demo-token");
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus(),
                "GET with a valid token must pass and carry the resolved "
                + "principal for downstream authorizer checks");
        var captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), any());
        var forwarded = captor.getValue();
        assertEquals("demo-user",
                forwarded.getUserPrincipal() == null ? null : forwarded.getUserPrincipal().getName(),
                "AuthenticatedHttpRequest must surface the resolved principal");
    }

    @Test
    void workspaceContentSurfacesAreDenyByDefaultEvenWithTheReadGateOff() throws Exception {
        // The plan, file-listing and file-content surfaces hold model-written
        // workspace internals — default-DENY regardless of the general read
        // plane (Invariant #6). Regression for the review finding that the
        // Workspace endpoints shipped riding the default-open read plane.
        var env = new MockEnvironment();
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorRejectingEverything(), env);
        for (var path : new String[]{
                "/api/admin/agents/coding-agent/plan",
                "/api/admin/agents/coding-agent/files",
                "/api/admin/agents/coding-agent/files/content"}) {
            var res = new MockHttpServletResponse();
            var chain = Mockito.mock(FilterChain.class);
            filter.doFilter(new MockHttpServletRequest("GET", path), res, chain);
            assertEquals(401, res.getStatus(),
                    "anonymous workspace content read must be denied by default: " + path);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void workspaceOwnersDiscoveryStaysOpenSoTheConsoleProbeDoesNotError() throws Exception {
        // The owners endpoint returns only agent names + plan/fs booleans —
        // the same existence info /api/admin/agents already exposes — and the
        // console probes it on load for EVERY app. Gating it emitted a
        // spurious 401 resource error on consoles that never open the
        // Workspace tab (broke orchestration-primitives' no-JS-errors e2e).
        var env = new MockEnvironment();
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorRejectingEverything(), env);
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(
                "GET", "/api/admin/workspace/owners"), res, chain);

        assertEquals(200, res.getStatus(),
                "the workspace owners discovery probe must stay open by default");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void workspaceOptOutFlagReopensTheWorkspaceReads() throws Exception {
        var env = new MockEnvironment()
                .withProperty("atmosphere.admin.workspace-read-auth-required", "false");
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorRejectingEverything(), env);
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(
                "GET", "/api/admin/agents/coding-agent/files/content"), res, chain);

        assertEquals(200, res.getStatus(),
                "the explicit demo opt-out must reopen the workspace reads");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void workspaceGateDoesNotTouchTheGeneralReadPlane() throws Exception {
        var env = new MockEnvironment();
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorRejectingEverything(), env);
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/admin/overview"), res, chain);

        assertEquals(200, res.getStatus(),
                "non-workspace admin reads keep the pre-existing default-open posture");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void workspaceReadWithAValidTokenPassesUnderTheDefaultGate() throws Exception {
        var env = new MockEnvironment();
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorAccepting("demo-token", "demo-user"), env);
        var req = new MockHttpServletRequest("GET", "/api/admin/agents/coding-agent/plan");
        req.addHeader("X-Atmosphere-Auth", "demo-token");
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus(),
                "an authenticated workspace read must pass the default gate");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void readAuthEnabled_writeAnonymousStillPassesToEndpointGuard() throws Exception {
        // Writes have their own guardWrite gate on the endpoint; the filter
        // only gates reads when the flag is on. An anonymous POST must
        // reach the endpoint so the endpoint returns the canonical 401
        // with audit log entry (not the filter's bare 401). Single source
        // of truth for the write decision.
        var env = new MockEnvironment()
                .withProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                tokenValidatorRejectingEverything(), env);
        var req = new MockHttpServletRequest("POST", "/api/admin/broadcasters/broadcast");
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus(),
                "anonymous POST must reach the endpoint — guardWrite handles "
                + "the 401 there and records the audit entry; the filter stays "
                + "out of write-side decisions");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void readAuthEnabled_noValidatorBean_anonymousGetStillDenied() throws Exception {
        // The filter is now registered unconditionally, so when no
        // TokenValidator bean exists the validator is null. With the read-auth
        // flag on, reads must fail closed (no principal can ever be resolved)
        // rather than the flag being silently ignored — the bug was that
        // @ConditionalOnBean skipped the whole filter without a validator.
        var env = new MockEnvironment()
                .withProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(null, env);
        var req = new MockHttpServletRequest("GET", "/api/admin/overview");
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertEquals(401, res.getStatus(),
                "with the flag on and no validator, admin reads must be denied "
                + "(fail closed), not silently allowed");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void noValidatorBean_flagOff_anonymousGetPassesThrough() throws Exception {
        // Default posture with no auth stack: filter present but inert.
        var filter = new AtmosphereAdminAutoConfiguration.AdminApiAuthFilter(
                null, new MockEnvironment());
        var req = new MockHttpServletRequest("GET", "/api/admin/overview");
        var res = new MockHttpServletResponse();
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus(),
                "no validator and flag off must remain an open read plane");
        verify(chain, times(1)).doFilter(any(), any());
    }

    private static TokenValidator tokenValidatorRejectingEverything() {
        var v = Mockito.mock(TokenValidator.class);
        when(v.validate(Mockito.anyString()))
                .thenReturn(new TokenValidator.Invalid("rejected"));
        return v;
    }

    private static TokenValidator tokenValidatorAccepting(String token, String principalName) {
        var v = Mockito.mock(TokenValidator.class);
        Principal p = () -> principalName;
        when(v.validate(token)).thenReturn(new TokenValidator.Valid(p, java.util.Map.of()));
        when(v.validate(Mockito.argThat(t -> t != null && !t.equals(token))))
                .thenReturn(new TokenValidator.Invalid("mismatch"));
        return v;
    }
}
