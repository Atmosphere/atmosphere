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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry mapping tool names to their {@link ToolAuthorization}
 * requirements. Populated by {@link DefaultToolRegistry} when it scans
 * {@link org.atmosphere.ai.annotation.Authorize @Authorize} annotations on
 * {@link org.atmosphere.ai.annotation.AiTool @AiTool} methods, consulted by
 * {@link ToolExecutionHelper#executeWithApproval} during dispatch.
 *
 * <p>Stored as a side-table rather than a {@link ToolDefinition} component
 * so adding the field does not force every {@code ToolDefinition}
 * construction site (record canonical constructor, builders, tests) to
 * change in lockstep.</p>
 *
 * <p>Authorization is registered globally by tool name. Tool names are
 * already required to be unique across the runtime
 * ({@link DefaultToolRegistry} rejects duplicates), so the global key space
 * does not collide with the per-endpoint tool exposure model.</p>
 */
public final class ToolAuthorizationRegistry {

    private static final ConcurrentHashMap<String, ToolAuthorization> AUTHORIZATIONS =
            new ConcurrentHashMap<>();

    private ToolAuthorizationRegistry() {
    }

    /**
     * Register an authorization requirement for a tool. Empty
     * authorizations are a no-op so callers can register unconditionally
     * without first checking {@link ToolAuthorization#isEmpty()}.
     */
    public static void register(String toolName, ToolAuthorization authorization) {
        if (toolName == null || toolName.isBlank() || authorization == null
                || authorization.isEmpty()) {
            return;
        }
        AUTHORIZATIONS.put(toolName, authorization);
    }

    /**
     * Look up the authorization requirement for a tool. Returns
     * {@link ToolAuthorization#NONE} when nothing was registered, so
     * callers can always call {@link ToolAuthorization#isAuthorized}
     * without a null check.
     */
    public static ToolAuthorization get(String toolName) {
        if (toolName == null) {
            return ToolAuthorization.NONE;
        }
        return AUTHORIZATIONS.getOrDefault(toolName, ToolAuthorization.NONE);
    }

    /**
     * Remove a tool's authorization requirement. Used by tests for
     * isolation; production code should treat the registry as
     * write-once-on-startup.
     */
    public static void unregister(String toolName) {
        if (toolName != null) {
            AUTHORIZATIONS.remove(toolName);
        }
    }

    /** Test-only: clear every registration. */
    public static void clear() {
        AUTHORIZATIONS.clear();
    }
}
