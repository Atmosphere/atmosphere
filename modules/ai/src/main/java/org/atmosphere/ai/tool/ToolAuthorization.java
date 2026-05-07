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

import java.util.Set;

/**
 * Authorization requirement attached to an {@link org.atmosphere.ai.annotation.AiTool}
 * method via {@link org.atmosphere.ai.annotation.Authorize}. Holds the role
 * and permission sets the caller must intersect with at least one of for the
 * tool dispatch to proceed.
 *
 * <p>Stored in {@link ToolAuthorizationRegistry} keyed by tool name rather
 * than threaded through the {@link ToolDefinition} record so the existing
 * {@code ToolDefinition} surface stays binary-compatible.</p>
 *
 * @param requiredRoles       roles that satisfy the requirement (any one suffices)
 * @param requiredPermissions permissions that satisfy the requirement (any one suffices)
 */
public record ToolAuthorization(Set<String> requiredRoles, Set<String> requiredPermissions) {

    /** No requirement — an empty authorization permits every caller. */
    public static final ToolAuthorization NONE = new ToolAuthorization(Set.of(), Set.of());

    public ToolAuthorization {
        requiredRoles = requiredRoles == null ? Set.of() : Set.copyOf(requiredRoles);
        requiredPermissions = requiredPermissions == null ? Set.of() : Set.copyOf(requiredPermissions);
    }

    /** @return {@code true} when no role or permission is required. */
    public boolean isEmpty() {
        return requiredRoles.isEmpty() && requiredPermissions.isEmpty();
    }

    /**
     * Decide whether a caller satisfies this authorization. Authorized when
     * (a) no role and no permission is required, OR (b) the caller's roles
     * intersect with {@link #requiredRoles}, OR (c) the caller's permissions
     * intersect with {@link #requiredPermissions}.
     *
     * <p>Default-deny: if the requirement is non-empty and the caller has
     * neither a matching role nor a matching permission, the call is denied.
     * Closes Correctness Invariant #6 (Security: every mutating surface
     * requires explicit authorization) for tool-level gating.</p>
     *
     * @param callerRoles       the caller's roles (may be empty / null)
     * @param callerPermissions the caller's permissions (may be empty / null)
     * @return {@code true} iff the caller is authorized to invoke the tool
     */
    public boolean isAuthorized(Set<String> callerRoles, Set<String> callerPermissions) {
        if (isEmpty()) {
            return true;
        }
        if (callerRoles != null) {
            for (var role : requiredRoles) {
                if (callerRoles.contains(role)) {
                    return true;
                }
            }
        }
        if (callerPermissions != null) {
            for (var perm : requiredPermissions) {
                if (callerPermissions.contains(perm)) {
                    return true;
                }
            }
        }
        return false;
    }
}
