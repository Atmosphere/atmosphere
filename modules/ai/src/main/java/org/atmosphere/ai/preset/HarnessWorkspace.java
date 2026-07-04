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
package org.atmosphere.ai.preset;

import java.nio.file.Path;

/**
 * Workspace-root resolution shared by the harness planning / filesystem
 * primitives — the same {@code atmosphere.workspace.root} sysprop /
 * {@code ATMOSPHERE_WORKSPACE_ROOT} env / {@code ~/.atmosphere/workspace}
 * fallback chain {@code AgentProcessor.buildFoundationPrimitives} and
 * {@code CoordinatorProcessor.wireFoundationPrimitives} use, so plans and
 * files land beside the agent's {@code FileSystemAgentState} subtree.
 */
public final class HarnessWorkspace {

    private HarnessWorkspace() {
    }

    /**
     * The configured workspace root: the {@code atmosphere.workspace.root}
     * system property, else {@code ATMOSPHERE_WORKSPACE_ROOT}, else
     * {@code {user.home}/.atmosphere/workspace}.
     *
     * @return the workspace root path, never {@code null}
     */
    public static Path root() {
        var root = System.getProperty("atmosphere.workspace.root");
        if (root == null) {
            root = System.getenv("ATMOSPHERE_WORKSPACE_ROOT");
        }
        if (root == null) {
            root = System.getProperty("user.home") + "/.atmosphere/workspace";
        }
        return Path.of(root);
    }

    /**
     * The per-owner subtree under the workspace root, e.g.
     * {@code {root}/agents/{name}} or {@code {root}/endpoints/{name}}. The
     * owner name is sanitized to one safe path segment.
     *
     * @param category the owner category ({@code agents}, {@code coordinators},
     *                 {@code endpoints})
     * @param owner    the owner name (agent name or endpoint path)
     * @return the owner's workspace root
     */
    public static Path ownerRoot(String category, String owner) {
        return root().resolve(category).resolve(sanitize(owner));
    }

    /**
     * Reduce an arbitrary owner name (an agent name or an endpoint path like
     * {@code /atmosphere/chat}) to one filesystem-safe path segment: every
     * character outside {@code [A-Za-z0-9._-]} becomes {@code -}, leading
     * separators drop, and a blank / dot-only result collapses to
     * {@code default} so the result can never traverse (Correctness
     * Invariant #4).
     *
     * @param name the raw owner name (may be {@code null})
     * @return a non-blank, traversal-safe segment
     */
    public static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        var cleaned = name.trim()
                .replaceAll("^[/\\\\]+", "")
                .replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isBlank() || cleaned.chars().allMatch(c -> c == '.' || c == '-')) {
            return "default";
        }
        return cleaned;
    }
}
