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

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test against a realistic OpenClaw-shaped workspace. The
 * fixture under {@code src/test/resources/fixtures/openclaw} mirrors the
 * canonical OpenClaw layout the v0.5 plan locked ({@code AGENTS.md} +
 * {@code SOUL.md} + {@code USER.md} + {@code IDENTITY.md} +
 * {@code MEMORY.md} + {@code memory/YYYY-MM-DD.md} + {@code skills/*}) plus
 * Atmosphere extension files ({@code CHANNELS.md}, {@code MCP.md}) that
 * OpenClaw ignores.
 *
 * <p>This test is the closest we have to "does an OpenClaw workspace run
 * on Atmosphere without conversion?" until the full cross-runtime contract
 * matrix lands in Phase 4.</p>
 */
class OpenClawCompatTest {

    private Path fixtureRoot() throws URISyntaxException, IOException {
        var resource = getClass().getClassLoader().getResource("fixtures/openclaw");
        assertNotNull(resource, "openclaw fixture resource not found on classpath");
        var fromClasspath = Path.of(resource.toURI());
        // Copy the read-only classpath fixture into a writable temp dir so
        // the adapter's read paths run against real filesystem semantics
        // identical to production.
        var copy = Files.createTempDirectory("openclaw-fixture-");
        copyDirectory(fromClasspath, copy);
        return copy;
    }

    @Test
    void openClawWorkspaceLoadsIdenticallyOnAtmosphere() throws Exception {
        var root = fixtureRoot();
        var loader = new AgentWorkspaceLoader(java.util.List.of(
                new OpenClawWorkspaceAdapter(),
                new AtmosphereNativeWorkspaceAdapter()));

        var def = loader.load(root);

        assertEquals("openclaw", def.adapterName(),
                "OpenClaw adapter must win over the native fallback");
        assertEquals("pierre", def.name(),
                "name: pierre in IDENTITY.md overrides the directory name");

        assertTrue(def.identity().contains("name: pierre"));
        assertTrue(def.persona().contains("Calm"));
        assertTrue(def.userProfile().contains("ChefFamille"));
        assertTrue(def.operatingRules().contains("Cite the crew member"));

        // Composed system prompt contains all four sections.
        var prompt = def.systemPrompt();
        assertTrue(prompt.contains("## Identity"));
        assertTrue(prompt.contains("## Persona"));
        assertTrue(prompt.contains("## User"));
        assertTrue(prompt.contains("## Operating rules"));

        // Atmosphere extensions surfaced but OpenClaw canonical MEMORY.md
        // is NOT surfaced through the extension map — memory is read by
        // AgentState at a different layer. This prevents an OpenClaw file
        // from bleeding into the Atmosphere extension set.
        assertTrue(def.atmosphereExtensions().containsKey("CHANNELS.md"));
        assertTrue(def.atmosphereExtensions().containsKey("MCP.md"));
        assertFalse(def.atmosphereExtensions().containsKey("MEMORY.md"));
        assertFalse(def.atmosphereExtensions().containsKey("AGENTS.md"));

        // Skills discovered via the canonical path.
        assertEquals(1, def.skillPaths().size());
        assertTrue(def.skillPaths().get(0).toString().endsWith("example-skill/SKILL.md"));
    }

    @Test
    void openClawDirectoryWithoutAgentsMdIsRejected() throws Exception {
        var tmp = Files.createTempDirectory("no-agents-md-");
        // Write SOUL.md but intentionally omit AGENTS.md — OpenClaw's
        // required marker file. The adapter must NOT claim this workspace.
        Files.writeString(tmp.resolve("SOUL.md"), "Calm", StandardCharsets.UTF_8);
        var adapter = new OpenClawWorkspaceAdapter();
        assertFalse(adapter.supports(tmp),
                "OpenClaw adapter must decline a directory without AGENTS.md");
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.sorted(Comparator.naturalOrder()).forEach(src -> {
                try {
                    var rel = source.relativize(src);
                    var dest = target.resolve(rel.toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
