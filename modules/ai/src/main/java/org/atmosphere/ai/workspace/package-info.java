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
/**
 * Agent-as-artifact primitives. {@link org.atmosphere.ai.workspace.AgentWorkspace}
 * is the SPI; {@link org.atmosphere.ai.workspace.AgentWorkspaceLoader}
 * discovers adapters via {@link java.util.ServiceLoader} and picks one by
 * priority-ordered {@code supports()} probe.
 *
 * <p>Built-in adapters:</p>
 * <ul>
 *   <li>{@link org.atmosphere.ai.workspace.OpenClawWorkspaceAdapter} — reads
 *       the OpenClaw canonical workspace layout (AGENTS.md + SOUL.md +
 *       USER.md + IDENTITY.md + skills/)</li>
 *   <li>{@link org.atmosphere.ai.workspace.AtmosphereNativeWorkspaceAdapter}
 *       — fallback that accepts any directory</li>
 * </ul>
 *
 * <h2>Loading a workspace</h2>
 *
 * Set {@code atmosphere.workspace} (system property, {@code ATMOSPHERE_WORKSPACE}
 * env var, framework init-param, or — under Spring Boot — the
 * {@code atmosphere.workspace} application property) to a filesystem path or a
 * {@code classpath:} resource that resolves to a real directory. When set,
 * {@code AgentProcessor} / {@code CoordinatorProcessor} load it and
 * {@link org.atmosphere.ai.workspace.WorkspaceExtensions} applies its
 * Atmosphere extension files. Unset = no workspace is loaded.
 *
 * <h2>Atmosphere extension files</h2>
 *
 * The OpenClaw spec ignores these; Atmosphere reads them and wires them
 * (see {@link org.atmosphere.ai.workspace.WorkspaceExtensions}):
 * <ul>
 *   <li><b>RUNTIME.md</b> — {@code key: value} lines ({@code model},
 *       {@code mode}, {@code api-key}, {@code base-url}, {@code temperature},
 *       {@code max-tokens}, {@code top-p}, {@code stop}) applied to the
 *       <em>process-wide</em> {@code AiConfig} (last workspace loaded wins).</li>
 *   <li><b>PERMISSIONS.md</b> — {@code deny:} / {@code deny-regex:} /
 *       {@code allow:} / {@code allow-regex:} / {@code require-role:} directives
 *       compiled to {@code GovernancePolicy} instances and installed into the
 *       framework policy bag (content / role gates; no filesystem ACLs).</li>
 *   <li><b>SKILLS.md</b> and the discovered {@code skills/&#42;/SKILL.md} files
 *       — appended to the agent's system prompt.</li>
 *   <li><b>CHANNELS.md</b> — channel-type names selecting which channels the
 *       agent serves (credentials stay in the channel module's own config).</li>
 *   <li><b>MCP.md</b> — {@code name: https://&#8230;} outbound MCP servers whose
 *       tools are registered into the agent (entries without an {@code http(s)}
 *       endpoint, and per-request credentials, are out of scope).</li>
 * </ul>
 */
package org.atmosphere.ai.workspace;
