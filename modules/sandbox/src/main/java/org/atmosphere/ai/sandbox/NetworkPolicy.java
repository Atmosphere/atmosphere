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
package org.atmosphere.ai.sandbox;

import java.util.List;
import java.util.Objects;

/**
 * Egress policy for a sandbox. Coding agents typically need narrow
 * network access (GitHub for cloning, package registries for dependency
 * resolution) without exposing the full internet to LLM-generated code.
 *
 * <h2>Modes</h2>
 *
 * <ul>
 *   <li>{@link Mode#NONE} — no network. The default; matches
 *       {@code SandboxLimits.DEFAULT} for backward compatibility.</li>
 *   <li>{@link Mode#GIT_ONLY} — only reach common Git hosts
 *       (github.com, gitlab.com, bitbucket.org, codeberg.org). Useful
 *       for coding agents that clone repos.</li>
 *   <li>{@link Mode#ALLOWLIST} — reach hosts explicitly listed in
 *       {@link #allowedHosts()}. Caller supplies the list.</li>
 *   <li>{@link Mode#FULL} — unrestricted network. Must be opted into
 *       explicitly; the default stays {@code NONE} per Correctness
 *       Invariant #6.</li>
 * </ul>
 *
 * @param mode         the policy mode
 * @param allowedHosts hosts the sandbox may reach when {@code mode} is
 *                     {@link Mode#ALLOWLIST}; empty for other modes
 */
public record NetworkPolicy(Mode mode, List<String> allowedHosts) {

    public enum Mode { NONE, GIT_ONLY, ALLOWLIST, FULL }

    /** Default egress policy — no network. */
    public static final NetworkPolicy NONE = new NetworkPolicy(Mode.NONE, List.of());

    /** Git hosting allowlist, applied by {@link Mode#GIT_ONLY}. */
    public static final List<String> GIT_HOSTS = List.of(
            "github.com", "gitlab.com", "bitbucket.org", "codeberg.org");

    /** {@link Mode#GIT_ONLY} policy reaching {@link #GIT_HOSTS}. */
    public static final NetworkPolicy GIT_ONLY = new NetworkPolicy(Mode.GIT_ONLY, GIT_HOSTS);

    /** Unrestricted egress — use sparingly. */
    public static final NetworkPolicy FULL = new NetworkPolicy(Mode.FULL, List.of());

    public NetworkPolicy {
        Objects.requireNonNull(mode, "mode");
        allowedHosts = allowedHosts != null ? List.copyOf(allowedHosts) : List.of();
        if (mode == Mode.ALLOWLIST && allowedHosts.isEmpty()) {
            throw new IllegalArgumentException(
                    "ALLOWLIST policy requires at least one allowed host");
        }
    }

    /** Construct an allowlist policy. */
    public static NetworkPolicy allowlist(String... hosts) {
        return new NetworkPolicy(Mode.ALLOWLIST, List.of(hosts));
    }

    /** {@code true} if the sandbox has any network egress whatsoever. */
    public boolean hasEgress() {
        return mode != Mode.NONE;
    }
}
