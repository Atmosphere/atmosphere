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
import java.util.List;
import java.util.Map;

/**
 * Parsed representation of an agent-as-artifact. Produced by an
 * {@link AgentWorkspace} adapter reading a workspace directory.
 *
 * <p>The identity / persona / user profile / operating rules are surfaced
 * both individually (so UIs and admin endpoints can render or edit them
 * independently) and composed into {@link #systemPrompt()} ready to prefix
 * every conversation turn.</p>
 *
 * @param name                   a stable agent identifier derived from the
 *                               workspace or inferred from {@code IDENTITY.md}
 * @param adapterName            the name of the {@link AgentWorkspace} adapter
 *                               that produced this definition
 * @param workspaceRoot          absolute, normalized path to the workspace
 *                               directory
 * @param identity               raw content of the identity source (e.g.
 *                               {@code IDENTITY.md})
 * @param persona                raw content of the persona source (e.g.
 *                               {@code SOUL.md})
 * @param userProfile            raw content of the user profile (e.g.
 *                               {@code USER.md})
 * @param operatingRules         raw content of the operating rules (e.g.
 *                               {@code AGENTS.md})
 * @param systemPrompt           the composed system prompt that prefixes each
 *                               conversation turn
 * @param skillPaths             paths to skill files discovered in the
 *                               workspace, in precedence order
 * @param atmosphereExtensions   map of Atmosphere-specific extension file
 *                               names (e.g. {@code "CHANNELS.md"}) to their
 *                               raw Markdown contents. Empty map when no
 *                               extensions are present. Downstream primitives
 *                               ({@code ToolExtensibilityPoint},
 *                               {@code AgentIdentity}, etc.) parse these
 *                               further according to their own schemas.
 */
public record AgentDefinition(
        String name,
        String adapterName,
        Path workspaceRoot,
        String identity,
        String persona,
        String userProfile,
        String operatingRules,
        String systemPrompt,
        List<Path> skillPaths,
        Map<String, String> atmosphereExtensions) {

    public AgentDefinition {
        skillPaths = skillPaths != null ? List.copyOf(skillPaths) : List.of();
        atmosphereExtensions = atmosphereExtensions != null
                ? Map.copyOf(atmosphereExtensions)
                : Map.of();
    }
}
