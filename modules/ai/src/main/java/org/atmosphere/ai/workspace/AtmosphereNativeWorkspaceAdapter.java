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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Fallback {@link AgentWorkspace} adapter for directories that contain no
 * recognized convention. Returns an {@link AgentDefinition} with empty
 * bootstrap content and no discovered skills — the workspace itself is
 * valid, it just has no authored identity yet.
 *
 * <p>This adapter accepts any existing directory, so it must be consulted
 * last during adapter selection. Its {@link #priority()} is deliberately
 * set to {@link Integer#MAX_VALUE}.</p>
 */
public final class AtmosphereNativeWorkspaceAdapter implements AgentWorkspace {

    public static final String NAME = "atmosphere-native";

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereNativeWorkspaceAdapter.class);

    @Override
    public boolean supports(Path workspaceRoot) {
        return workspaceRoot != null && Files.isDirectory(workspaceRoot);
    }

    @Override
    public AgentDefinition load(Path workspaceRoot) {
        var root = workspaceRoot.toAbsolutePath().normalize();
        if (!supports(root)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + root);
        }

        // Respect a README.md as operating rules if present, so a directory
        // with just a README works as a minimal workspace.
        var readme = root.resolve("README.md");
        var rules = "";
        if (Files.exists(readme)) {
            try {
                rules = Files.readString(readme, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.warn("Failed to read README.md: {}", e.getMessage());
            }
        }

        var systemPrompt = rules.isBlank()
                ? ""
                : "## Operating rules\n\n" + rules.trim();

        var name = root.getFileName() != null ? root.getFileName().toString() : "agent";

        return new AgentDefinition(
                name,
                NAME,
                root,
                "",
                "",
                "",
                rules,
                systemPrompt,
                List.of(),
                Map.of());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }
}
