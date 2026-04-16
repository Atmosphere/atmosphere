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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkspaceLoaderTest {

    @Test
    void openClawAdapterLoadsCanonicalWorkspace(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"),
                "Be helpful and direct.", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("SOUL.md"),
                "Calm and focused.", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("USER.md"),
                "Address the user as ChefFamille.", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("IDENTITY.md"),
                "name: pierre\nI am Pierre.", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("MEMORY.md"),
                "- [id:xyz|at:2026-04-15T00:00:00Z] ChefFamille prefers bun",
                StandardCharsets.UTF_8);

        // Atmosphere extensions colocated
        Files.writeString(root.resolve("CHANNELS.md"),
                "- slack\n- web", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("MCP.md"),
                "- github: os-keychain", StandardCharsets.UTF_8);

        // Skills
        var skillsDir = root.resolve("skills").resolve("example-skill");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"),
                "# Example skill", StandardCharsets.UTF_8);

        var loader = new AgentWorkspaceLoader(List.of(
                new OpenClawWorkspaceAdapter(),
                new AtmosphereNativeWorkspaceAdapter()));

        var def = loader.load(root);

        assertEquals("openclaw", def.adapterName());
        assertEquals("pierre", def.name());
        assertEquals(root.toAbsolutePath().normalize(), def.workspaceRoot());
        assertTrue(def.identity().contains("I am Pierre"));
        assertEquals("Calm and focused.", def.persona());
        assertEquals("Address the user as ChefFamille.", def.userProfile());
        assertEquals("Be helpful and direct.", def.operatingRules());
        assertTrue(def.systemPrompt().contains("## Identity"));
        assertTrue(def.systemPrompt().contains("I am Pierre"));
        assertTrue(def.systemPrompt().contains("## Persona"));
        assertTrue(def.systemPrompt().contains("## User"));
        assertTrue(def.systemPrompt().contains("## Operating rules"));

        // Atmosphere extensions surfaced
        assertEquals("- slack\n- web", def.atmosphereExtensions().get("CHANNELS.md"));
        assertEquals("- github: os-keychain", def.atmosphereExtensions().get("MCP.md"));
        assertFalse(def.atmosphereExtensions().containsKey("PERMISSIONS.md"));

        // Skill discovered
        assertEquals(1, def.skillPaths().size());
        assertEquals("SKILL.md", def.skillPaths().get(0).getFileName().toString());
    }

    @Test
    void openClawAdapterRejectsDirectoryWithoutAgentsMd(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "Not an OpenClaw workspace",
                StandardCharsets.UTF_8);
        var adapter = new OpenClawWorkspaceAdapter();
        assertFalse(adapter.supports(root));
    }

    @Test
    void openClawAdapterLoadRefusesUnsupportedRoot(@TempDir Path root) {
        var adapter = new OpenClawWorkspaceAdapter();
        assertThrows(IllegalArgumentException.class, () -> adapter.load(root));
    }

    @Test
    void nativeAdapterAcceptsAnyDirectory(@TempDir Path root) {
        var adapter = new AtmosphereNativeWorkspaceAdapter();
        assertTrue(adapter.supports(root));
        var def = adapter.load(root);
        assertEquals("atmosphere-native", def.adapterName());
        assertTrue(def.identity().isEmpty());
        assertTrue(def.skillPaths().isEmpty());
    }

    @Test
    void nativeAdapterReadsReadmeAsOperatingRules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "Do the thing.", StandardCharsets.UTF_8);
        var adapter = new AtmosphereNativeWorkspaceAdapter();
        var def = adapter.load(root);
        assertEquals("Do the thing.", def.operatingRules());
        assertTrue(def.systemPrompt().contains("Do the thing."));
    }

    @Test
    void loaderPicksOpenClawOverNative(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "rules", StandardCharsets.UTF_8);
        var loader = new AgentWorkspaceLoader(List.of(
                new AtmosphereNativeWorkspaceAdapter(),
                new OpenClawWorkspaceAdapter()));
        // Despite native being listed first, the loader sorts by priority,
        // so OpenClaw (priority 10) wins over native (priority MAX_VALUE).
        assertEquals("openclaw", loader.load(root).adapterName());
    }

    @Test
    void loaderFallsBackToNativeWhenNoSpecificMatch(@TempDir Path root) {
        var loader = new AgentWorkspaceLoader(List.of(
                new OpenClawWorkspaceAdapter(),
                new AtmosphereNativeWorkspaceAdapter()));
        // Directory exists but has no OpenClaw marker file. Native accepts it.
        assertEquals("atmosphere-native", loader.load(root).adapterName());
    }

    @Test
    void loaderThrowsWhenNoAdapterMatches(@TempDir Path root) throws Exception {
        var loader = new AgentWorkspaceLoader(List.of(new OpenClawWorkspaceAdapter()));
        // OpenClaw rejects a directory without AGENTS.md, and native is not
        // in the list, so no adapter accepts the root.
        assertThrows(IllegalStateException.class, () -> loader.load(root));
    }

    @Test
    void serviceLoaderDiscoversBuiltIns(@TempDir Path root) {
        var loader = new AgentWorkspaceLoader();
        var names = loader.adapters().stream().map(AgentWorkspace::name).toList();
        assertTrue(names.contains("openclaw"),
                "openclaw adapter should be discovered via ServiceLoader");
        assertTrue(names.contains("atmosphere-native"),
                "native adapter should be discovered via ServiceLoader");
    }

    @Test
    void identityNameFallsBackToDirectoryName(@TempDir Path parent) throws Exception {
        var named = parent.resolve("my-special-agent");
        Files.createDirectories(named);
        Files.writeString(named.resolve("AGENTS.md"), "rules", StandardCharsets.UTF_8);
        Files.writeString(named.resolve("IDENTITY.md"), "no name marker here",
                StandardCharsets.UTF_8);
        var def = new OpenClawWorkspaceAdapter().load(named);
        assertEquals("my-special-agent", def.name());
    }
}
