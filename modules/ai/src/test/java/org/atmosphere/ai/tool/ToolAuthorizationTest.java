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
package org.atmosphere.ai.tool;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ToolAuthorization}, {@link ToolAuthorizationRegistry}, and
 * the {@link ToolExecutionHelper} integration that gates tool dispatch on
 * {@link org.atmosphere.ai.annotation.Authorize}.
 *
 * <p>Closes Correctness Invariant #6 (Security: every mutating surface
 * requires explicit authorization) for tool-level gating — an
 * {@code @Authorize}-decorated tool fails closed against an unauthenticated
 * or under-privileged caller.</p>
 */
class ToolAuthorizationTest {

    @AfterEach
    void cleanup() {
        ToolAuthorizationRegistry.clear();
    }

    // -- ToolAuthorization decision logic --

    @Test
    void emptyAuthorizationAdmitsEveryone() {
        var auth = ToolAuthorization.NONE;
        assertTrue(auth.isEmpty());
        assertTrue(auth.isAuthorized(Set.of(), Set.of()));
        assertTrue(auth.isAuthorized(null, null));
    }

    @Test
    void rolesBasedAuthorizationAdmitsCallerWithMatchingRole() {
        var auth = new ToolAuthorization(Set.of("admin", "support"), Set.of());
        assertTrue(auth.isAuthorized(Set.of("admin"), Set.of()));
        assertTrue(auth.isAuthorized(Set.of("support", "viewer"), Set.of()));
    }

    @Test
    void rolesBasedAuthorizationDeniesCallerWithoutRole() {
        var auth = new ToolAuthorization(Set.of("admin"), Set.of());
        assertFalse(auth.isAuthorized(Set.of("viewer"), Set.of()),
                "caller missing required role must be denied (Invariant #6 default-deny)");
        assertFalse(auth.isAuthorized(Set.of(), Set.of()),
                "unauthenticated caller must be denied");
        assertFalse(auth.isAuthorized(null, null),
                "null caller-role-set must be denied");
    }

    @Test
    void permissionsBasedAuthorizationAdmitsCallerWithMatchingPermission() {
        var auth = new ToolAuthorization(Set.of(), Set.of("user:delete"));
        assertTrue(auth.isAuthorized(Set.of(), Set.of("user:delete")));
        assertTrue(auth.isAuthorized(Set.of("viewer"), Set.of("user:read", "user:delete")));
    }

    @Test
    void permissionsBasedAuthorizationDeniesCallerWithoutPermission() {
        var auth = new ToolAuthorization(Set.of(), Set.of("user:delete"));
        assertFalse(auth.isAuthorized(Set.of("admin"), Set.of("user:read")));
    }

    @Test
    void roleOrPermissionMatchSatisfies() {
        var auth = new ToolAuthorization(Set.of("admin"), Set.of("user:delete"));
        assertTrue(auth.isAuthorized(Set.of("admin"), Set.of()),
                "matching role alone satisfies");
        assertTrue(auth.isAuthorized(Set.of(), Set.of("user:delete")),
                "matching permission alone satisfies");
        assertFalse(auth.isAuthorized(Set.of("viewer"), Set.of("user:read")),
                "neither match must be denied");
    }

    @Test
    void registryReturnsNoneForUnregisteredTool() {
        assertSame(ToolAuthorization.NONE, ToolAuthorizationRegistry.get("nonexistent"));
        assertSame(ToolAuthorization.NONE, ToolAuthorizationRegistry.get(null));
    }

    @Test
    void registryStoresAndReturnsAuthorization() {
        var auth = new ToolAuthorization(Set.of("admin"), Set.of());
        ToolAuthorizationRegistry.register("delete_user", auth);

        assertEquals(Set.of("admin"),
                ToolAuthorizationRegistry.get("delete_user").requiredRoles());
    }

    @Test
    void registryDropsEmptyAuthorizations() {
        ToolAuthorizationRegistry.register("noop_tool", ToolAuthorization.NONE);
        assertSame(ToolAuthorization.NONE,
                ToolAuthorizationRegistry.get("noop_tool"),
                "empty authorization registration must be a no-op");
    }

    @Test
    void registryUnregisterRemovesEntry() {
        ToolAuthorizationRegistry.register("temp",
                new ToolAuthorization(Set.of("admin"), Set.of()));
        ToolAuthorizationRegistry.unregister("temp");
        assertSame(ToolAuthorization.NONE, ToolAuthorizationRegistry.get("temp"));
    }

    // -- ToolExecutionHelper integration: authorization gate --

    @Test
    void executeWithApprovalRunsToolWhenNoAuthorizationRegistered() {
        var tool = ToolDefinition.builder("free_tool", "no auth required")
                .executor(args -> "ok")
                .build();
        // Caller is unauthenticated — but the tool has no @Authorize.
        var resource = mockResource(null, null);
        var injectables = Map.<Class<?>, Object>of(AtmosphereResource.class, resource);

        var result = ToolExecutionHelper.executeWithApproval(
                "free_tool", tool, Map.of(), null, null, null, injectables);

        assertEquals("ok", result, "tool without @Authorize must run regardless of caller roles");
    }

    @Test
    void executeWithApprovalDeniesUnauthenticatedCallerWhenAuthorizationRequired() {
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user",
                new ToolAuthorization(Set.of("admin"), Set.of()));

        var resource = mockResource(null, null); // no roles → unauthenticated
        var injectables = Map.<Class<?>, Object>of(AtmosphereResource.class, resource);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_user", tool, Map.of(), null, null, null, injectables);

        assertTrue(result.contains("\"status\":\"cancelled\""),
                "unauthenticated caller must be denied (default-deny)");
        assertTrue(result.contains("insufficient authorization"));
    }

    @Test
    void executeWithApprovalAdmitsCallerWithMatchingRole() {
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user",
                new ToolAuthorization(Set.of("admin"), Set.of()));

        var resource = mockResource(Set.of("admin", "support"), null);
        var injectables = Map.<Class<?>, Object>of(AtmosphereResource.class, resource);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_user", tool, Map.of(), null, null, null, injectables);

        assertEquals("deleted", result, "caller with matching role must be admitted");
    }

    @Test
    void executeWithApprovalDeniesCallerMissingRole() {
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user",
                new ToolAuthorization(Set.of("admin"), Set.of()));

        var resource = mockResource(Set.of("viewer"), null);
        var injectables = Map.<Class<?>, Object>of(AtmosphereResource.class, resource);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_user", tool, Map.of(), null, null, null, injectables);

        assertTrue(result.contains("\"status\":\"cancelled\""),
                "caller without matching role must be denied");
    }

    @Test
    void executeWithApprovalReadsRolesFromCommaSeparatedString() {
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user",
                new ToolAuthorization(Set.of("admin"), Set.of()));

        // Apps coming from environment variables / JWT claims often store
        // roles as a CSV string rather than a Set. The extractor must handle it.
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userRoles")).thenReturn("viewer, admin , support");
        when(request.getAttribute("ai.userPermissions")).thenReturn(null);

        var injectables = Map.<Class<?>, Object>of(AtmosphereResource.class, resource);
        var result = ToolExecutionHelper.executeWithApproval(
                "delete_user", tool, Map.of(), null, null, null, injectables);

        assertEquals("deleted", result, "CSV role attribute must parse correctly");
    }

    @Test
    void executeWithApprovalReadsRolesFromCollection() {
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user",
                new ToolAuthorization(Set.of("admin"), Set.of()));

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        // Spring Security typically exposes a Collection<GrantedAuthority>.
        when(request.getAttribute("ai.userRoles")).thenReturn(List.of("admin", "support"));
        when(request.getAttribute("ai.userPermissions")).thenReturn(null);

        var injectables = Map.<Class<?>, Object>of(AtmosphereResource.class, resource);
        var result = ToolExecutionHelper.executeWithApproval(
                "delete_user", tool, Map.of(), null, null, null, injectables);

        assertEquals("deleted", result, "List role attribute must be honored");
    }

    @Test
    void executeWithApprovalAdmitsCallerWithMatchingPermission() {
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user",
                new ToolAuthorization(Set.of(), Set.of("user:delete")));

        var resource = mockResource(Set.of("viewer"), Set.of("user:read", "user:delete"));
        var injectables = Map.<Class<?>, Object>of(AtmosphereResource.class, resource);

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_user", tool, Map.of(), null, null, null, injectables);

        assertEquals("deleted", result, "permission match must be sufficient");
    }

    @Test
    void executeWithApprovalDeniesEvenWithoutResourceWhenAuthorizationRequired() {
        // No AtmosphereResource in injectables — completely unauthenticated.
        // Default-deny must hold.
        var tool = ToolDefinition.builder("delete_user", "permanently delete user")
                .executor(args -> "deleted")
                .build();
        ToolAuthorizationRegistry.register("delete_user",
                new ToolAuthorization(Set.of("admin"), Set.of()));

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_user", tool, Map.of(), null, null, null, Map.of());

        assertTrue(result.contains("\"status\":\"cancelled\""),
                "missing AtmosphereResource must be treated as unauthenticated → denied");
    }

    private AtmosphereResource mockResource(Set<String> roles, Set<String> permissions) {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userRoles")).thenReturn(roles);
        when(request.getAttribute("ai.userPermissions")).thenReturn(permissions);
        return resource;
    }
}
