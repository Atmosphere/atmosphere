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

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.URI;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the opt-in admin-read auth gate on Quarkus
 * ({@code atmosphere.admin.http-read-auth-required}). Mirrors the
 * Spring Boot starter's {@code AdminApiAuthFilterReadGateTest} so
 * the admin auth posture is provably identical across both runtimes
 * (Correctness Invariant #7 — mode parity).
 */
class AdminReadAuthFilterTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("atmosphere.admin.http-read-auth-required");
        System.clearProperty("atmosphere.admin.auth.token");
    }

    @Test
    void readAuthDisabledByDefault_anonymousGetPasses() throws Exception {
        var filter = new AdminReadAuthFilter();
        var ctx = mockReadRequest("/api/admin/overview", null, null);
        filter.filter(ctx);
        verify(ctx, never()).abortWith(Mockito.any());
    }

    @Test
    void readAuthEnabled_anonymousGetReturns401() throws Exception {
        System.setProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AdminReadAuthFilter();
        var ctx = mockReadRequest("/api/admin/overview", null, null);

        filter.filter(ctx);

        var captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(401, captor.getValue().getStatus(),
                "anonymous read must be rejected with 401 when the gate is on");
    }

    @Test
    void readAuthEnabled_validTokenHeaderPasses() throws Exception {
        System.setProperty("atmosphere.admin.http-read-auth-required", "true");
        System.setProperty("atmosphere.admin.auth.token", "demo-token");
        var filter = new AdminReadAuthFilter();
        var ctx = mockReadRequest("/api/admin/overview", null, "demo-token");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(Mockito.any());
    }

    @Test
    void readAuthEnabled_wrongTokenRejected() throws Exception {
        System.setProperty("atmosphere.admin.http-read-auth-required", "true");
        System.setProperty("atmosphere.admin.auth.token", "demo-token");
        var filter = new AdminReadAuthFilter();
        var ctx = mockReadRequest("/api/admin/overview", null, "wrong");

        filter.filter(ctx);

        verify(ctx).abortWith(Mockito.any());
    }

    @Test
    void readAuthEnabled_jakartaSecurityPrincipalPasses() throws Exception {
        System.setProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AdminReadAuthFilter();
        Principal p = () -> "alice";
        var ctx = mockReadRequest("/api/admin/overview", p, null);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(Mockito.any());
    }

    @Test
    void readAuthEnabled_writeMethodPassesThrough() throws Exception {
        // Writes are gated by AdminResource.guardWrite; the filter should
        // not double-gate. The write-path 401 carries an audit entry; the
        // filter would only produce a bare 401 without one.
        System.setProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AdminReadAuthFilter();
        var ctx = Mockito.mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("POST");
        // Other getters unused on the write path.

        filter.filter(ctx);

        verify(ctx, never()).abortWith(Mockito.any());
    }

    @Test
    void nonAdminPathNotAffected() throws Exception {
        System.setProperty("atmosphere.admin.http-read-auth-required", "true");
        var filter = new AdminReadAuthFilter();
        var ctx = mockReadRequest("/public/agents", null, null);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(Mockito.any());
    }

    private static ContainerRequestContext mockReadRequest(
            String path, Principal principal, String authHeader) {
        var ctx = Mockito.mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("GET");
        var uri = Mockito.mock(UriInfo.class);
        // Strip leading slash — UriInfo.getPath() typically returns
        // the path without the leading slash in JAX-RS.
        when(uri.getPath()).thenReturn(path.startsWith("/") ? path.substring(1) : path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://h:8080" + path));
        when(ctx.getUriInfo()).thenReturn(uri);
        var sec = Mockito.mock(SecurityContext.class);
        when(sec.getUserPrincipal()).thenReturn(principal);
        when(ctx.getSecurityContext()).thenReturn(sec);
        when(ctx.getHeaderString("X-Atmosphere-Auth")).thenReturn(authHeader);
        return ctx;
    }
}
