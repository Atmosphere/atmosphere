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
 *       USER.md + IDENTITY.md + MEMORY.md + memory/ + skills/)</li>
 *   <li>{@link org.atmosphere.ai.workspace.AtmosphereNativeWorkspaceAdapter}
 *       — fallback that accepts any directory</li>
 * </ul>
 */
package org.atmosphere.ai.workspace;
