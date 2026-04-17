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
package org.atmosphere.admin;

/**
 * SPI for authorizing control plane operations. Implementations decide whether
 * a given principal may execute a control action.
 *
 * <p>The default implementation allows all operations. In production, bind a
 * concrete authorizer that checks tokens, roles, or other credentials.</p>
 *
 * @since 4.0
 */
public interface ControlAuthorizer {

    /**
     * Determine whether the given principal is allowed to execute the action.
     *
     * @param action    the action description (e.g. "broadcast", "disconnect")
     * @param target    the target identifier (e.g. broadcaster ID, resource UUID)
     * @param principal the authenticated principal, or {@code null} if anonymous
     * @return {@code true} if the action is authorized
     */
    boolean authorize(String action, String target, String principal);

    /**
     * Default authorizer that permits all operations.
     *
     * <p><b>Do not use in production.</b> The Spring Boot starter and
     * Quarkus extension log a WARN at startup when this authorizer is
     * active so the posture is visible to operators. For production
     * deployments prefer {@link #REQUIRE_PRINCIPAL} as a baseline and
     * layer tenant-specific checks on top.</p>
     */
    ControlAuthorizer ALLOW_ALL = (action, target, principal) -> true;

    /**
     * Default-deny authorizer. Every action is rejected regardless of
     * principal. The recommended baseline when the operator has not yet
     * wired a real authorizer — Correctness Invariant #6 (default deny).
     */
    ControlAuthorizer DENY_ALL = (action, target, principal) -> false;

    /**
     * Allows any action when an authenticated principal is present, denies
     * anonymous access. The recommended minimal baseline for production
     * deployments — authentication flows through the transport layer
     * (Spring Security, Quarkus security) and admin actions require a
     * resolved principal.
     */
    ControlAuthorizer REQUIRE_PRINCIPAL =
            (action, target, principal) -> principal != null && !principal.isBlank();
}
