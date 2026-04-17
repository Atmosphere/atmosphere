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
package org.atmosphere.ai.workspace;

import java.nio.file.Path;

/**
 * Agent-as-artifact primitive. An {@code AgentWorkspace} adapter parses a
 * directory on disk into a runnable {@link AgentDefinition}. Users bring
 * whatever workspace convention they prefer — OpenClaw, Claude Code, a
 * native Atmosphere layout — and Atmosphere reads it through the SPI.
 *
 * <h2>Adapter discovery</h2>
 *
 * Adapters are discovered via {@link java.util.ServiceLoader}. The
 * {@link AgentWorkspaceLoader} polls every registered adapter in priority
 * order and picks the first one whose {@link #supports(Path)} returns
 * {@code true}.
 *
 * <h2>Built-ins</h2>
 *
 * <ul>
 *   <li>{@link OpenClawWorkspaceAdapter} — OpenClaw canonical layout
 *       ({@code AGENTS.md} + {@code SOUL.md} + {@code USER.md} +
 *       {@code IDENTITY.md} + {@code MEMORY.md} + {@code memory/*} +
 *       {@code skills/*} + JSONL sessions under {@code agents/})</li>
 *   <li>{@link AtmosphereNativeWorkspaceAdapter} — minimal fallback for
 *       users without an existing workspace convention</li>
 * </ul>
 *
 * <h2>Atmosphere extensions</h2>
 *
 * Adapters read the OpenClaw-canonical files AND any Atmosphere-specific
 * extension files colocated in the same workspace (e.g. {@code CHANNELS.md},
 * {@code MCP.md}, {@code RUNTIME.md}, {@code PERMISSIONS.md},
 * {@code SKILLS.md}). OpenClaw ignores these; Atmosphere reads them and
 * surfaces the raw Markdown to downstream primitives via
 * {@link AgentDefinition#atmosphereExtensions()}.
 */
public interface AgentWorkspace {

    /**
     * Return {@code true} if this adapter recognizes the workspace layout
     * at the given root directory. Used by
     * {@link AgentWorkspaceLoader} to pick an adapter at load time.
     */
    boolean supports(Path workspaceRoot);

    /**
     * Load the agent definition from the workspace. Contract: callers may
     * rely on the returned {@link AgentDefinition#workspaceRoot()} equalling
     * the normalized {@code workspaceRoot} argument and
     * {@link AgentDefinition#adapterName()} equalling {@link #name()}.
     *
     * @throws IllegalArgumentException if {@link #supports(Path)} would have
     *         returned {@code false} for this root
     */
    AgentDefinition load(Path workspaceRoot);

    /**
     * A short, stable identifier for this adapter ({@code "openclaw"},
     * {@code "claude-code"}, {@code "atmosphere-native"}, etc.). Used for
     * logging and for the admin control plane to surface which adapter
     * loaded an agent.
     */
    String name();

    /**
     * Lower values run first during adapter selection. The
     * {@link AtmosphereNativeWorkspaceAdapter} has the highest priority
     * value so it is consulted last as a fallback. The default priority is
     * {@code 100}.
     */
    default int priority() {
        return 100;
    }
}
