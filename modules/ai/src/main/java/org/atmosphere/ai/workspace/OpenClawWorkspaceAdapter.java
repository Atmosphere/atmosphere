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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link AgentWorkspace} adapter for the OpenClaw canonical workspace layout.
 * An OpenClaw workspace authored without any Atmosphere extensions runs on
 * Atmosphere without conversion — that is the zero-config compatibility
 * promise.
 *
 * <h2>Recognized layout</h2>
 *
 * A workspace is considered OpenClaw-shaped when it contains
 * {@code AGENTS.md} at the root. Other canonical files ({@code SOUL.md},
 * {@code USER.md}, {@code IDENTITY.md}, {@code MEMORY.md}) are loaded when
 * present but none are individually required.
 *
 * <h2>Atmosphere extensions</h2>
 *
 * The following Atmosphere-specific files are loaded into
 * {@link AgentDefinition#atmosphereExtensions()} when present. OpenClaw
 * itself ignores them.
 *
 * <ul>
 *   <li>{@code CHANNELS.md}</li>
 *   <li>{@code MCP.md}</li>
 *   <li>{@code RUNTIME.md}</li>
 *   <li>{@code PERMISSIONS.md}</li>
 *   <li>{@code SKILLS.md}</li>
 * </ul>
 *
 * <h2>Skills</h2>
 *
 * Skills live at {@code <workspace>/skills/} and {@code <workspace>/.agents/skills/}.
 * The adapter walks both directories one level deep and reports any
 * {@code SKILL.md} files in precedence order (workspace first, project
 * second).
 */
public final class OpenClawWorkspaceAdapter implements AgentWorkspace {

    public static final String NAME = "openclaw";

    private static final Logger logger = LoggerFactory.getLogger(OpenClawWorkspaceAdapter.class);

    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String SOUL_FILE = "SOUL.md";
    private static final String USER_FILE = "USER.md";
    private static final String IDENTITY_FILE = "IDENTITY.md";

    private static final List<String> ATMO_EXTENSIONS = List.of(
            "CHANNELS.md", "MCP.md", "RUNTIME.md", "PERMISSIONS.md", "SKILLS.md");

    @Override
    public boolean supports(Path workspaceRoot) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return false;
        }
        return Files.exists(workspaceRoot.resolve(AGENTS_FILE));
    }

    @Override
    public AgentDefinition load(Path workspaceRoot) {
        var root = workspaceRoot.toAbsolutePath().normalize();
        if (!supports(root)) {
            throw new IllegalArgumentException(
                    "Not an OpenClaw workspace (missing AGENTS.md): " + root);
        }

        var identity = readIfPresent(root.resolve(IDENTITY_FILE));
        var persona = readIfPresent(root.resolve(SOUL_FILE));
        var userProfile = readIfPresent(root.resolve(USER_FILE));
        var operatingRules = readIfPresent(root.resolve(AGENTS_FILE));

        var systemPrompt = composeSystemPrompt(identity, persona, userProfile, operatingRules);

        var extensions = new LinkedHashMap<String, String>();
        for (var fileName : ATMO_EXTENSIONS) {
            var content = readIfPresent(root.resolve(fileName));
            if (!content.isBlank()) {
                extensions.put(fileName, content);
            }
        }

        var skillPaths = discoverSkills(root);
        var name = inferName(root, identity);

        return new AgentDefinition(
                name,
                NAME,
                root,
                identity,
                persona,
                userProfile,
                operatingRules,
                systemPrompt,
                skillPaths,
                extensions);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        // Consulted before the native fallback so an OpenClaw workspace is
        // never misclassified as a generic Atmosphere directory.
        return 10;
    }

    private static String readIfPresent(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to read workspace file {}: {}", path, e.getMessage());
            return "";
        }
    }

    private static String composeSystemPrompt(String identity, String persona,
                                              String userProfile, String operatingRules) {
        var sb = new StringBuilder();
        appendSection(sb, "Identity", identity);
        appendSection(sb, "Persona", persona);
        appendSection(sb, "User", userProfile);
        appendSection(sb, "Operating rules", operatingRules);
        return sb.toString().trim();
    }

    private static void appendSection(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("## ").append(title).append("\n\n").append(body.trim());
    }

    private static List<Path> discoverSkills(Path root) {
        var skills = new ArrayList<Path>();
        collectSkillsFrom(root.resolve("skills"), skills);
        collectSkillsFrom(root.resolve(".agents").resolve("skills"), skills);
        return skills;
    }

    private static void collectSkillsFrom(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> top = Files.list(dir)) {
            top.filter(Files::isDirectory).forEach(skillDir -> {
                var skillFile = skillDir.resolve("SKILL.md");
                if (Files.exists(skillFile)) {
                    out.add(skillFile.toAbsolutePath().normalize());
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to enumerate skills directory {}: {}", dir, e.getMessage());
        }
    }

    private static String inferName(Path root, String identityContent) {
        // Prefer an explicit "name:" marker at the top of IDENTITY.md so the
        // workspace directory can be renamed without changing the agent's
        // identity. Fall back to the directory name otherwise.
        if (identityContent != null && !identityContent.isBlank()) {
            for (var line : identityContent.lines().toList()) {
                var trimmed = line.trim();
                if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("name:")) {
                    var candidate = trimmed.substring("name:".length()).trim();
                    if (!candidate.isBlank()) {
                        return candidate;
                    }
                }
            }
        }
        return root.getFileName() != null ? root.getFileName().toString() : "agent";
    }
}
