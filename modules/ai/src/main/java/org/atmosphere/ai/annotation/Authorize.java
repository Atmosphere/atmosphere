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
package org.atmosphere.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative per-user authorization gate on an {@link AiTool} method.
 * Generalizes Atmosphere's {@code @RoomAuth} pattern to the tool level — the
 * tool dispatcher consults the caller's roles and permissions before
 * invocation and short-circuits with a {@code cancelled} response when the
 * caller cannot satisfy the requirement.
 *
 * <p><b>Default-deny semantics (Correctness Invariant #6).</b> If
 * {@code roles()} or {@code permissions()} are non-empty and the caller's
 * roles / permissions intersect with neither, the tool call is denied. An
 * empty annotation (the default) is a no-op — equivalent to no
 * {@code @Authorize} at all.</p>
 *
 * <p>The dispatcher resolves the caller's roles and permissions from the
 * {@code ai.userRoles} and {@code ai.userPermissions} request attributes on
 * the {@link org.atmosphere.cpr.AtmosphereResource} (each may be a
 * {@link java.util.Set Set&lt;String&gt;}, a {@link java.util.Collection},
 * or a comma-separated {@link String}). Applications wire these from their
 * authentication layer via an {@link org.atmosphere.ai.AiInterceptor},
 * a Spring {@code SecurityContext} bridge, etc.</p>
 *
 * <p>If the caller has neither a populated role set nor a populated
 * permission set, every {@code @Authorize}-annotated tool fails closed —
 * an unauthenticated user cannot run a privileged tool by default.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @AiTool(name = "delete_user", description = "Permanently delete a user account")
 * @Authorize(roles = {"admin", "support"})
 * public String deleteUser(@Param("userId") String userId) { ... }
 * }</pre>
 *
 * <p>Authorization is checked AFTER governance policy admission and BEFORE
 * the {@link RequiresApproval} gate, so a denied call never reaches the
 * human approver — and never appears in the approval registry where a
 * disconnect would have to release it. See
 * {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval} for
 * the dispatch order.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Authorize {

    /**
     * Roles that satisfy this authorization requirement. The caller must have
     * at least one of these roles in their {@code ai.userRoles} attribute set.
     * Defaults to an empty array — combined with an empty
     * {@link #permissions()}, this makes the annotation a no-op.
     */
    String[] roles() default {};

    /**
     * Permissions that satisfy this authorization requirement. The caller
     * must have at least one of these permissions in their
     * {@code ai.userPermissions} attribute set. Useful when the application
     * uses fine-grained permissions independent of roles (e.g. ABAC).
     */
    String[] permissions() default {};
}
