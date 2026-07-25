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
package org.atmosphere.ai.prompt;

import org.atmosphere.ai.PromptLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FilePromptRegistryTest {

    private final FilePromptRegistry registry = new FilePromptRegistry();

    @AfterEach
    public void tearDown() {
        System.clearProperty(FilePromptRegistry.PROMPT_DIR_PROPERTY);
        PromptLoader.clearCache();
    }

    @Test
    public void resolvesExactClasspathVersion() {
        assertEquals("Greeter prompt v1.", registry.content("greeter", "v1").orElseThrow());
        assertEquals("Greeter prompt v2.", registry.content("greeter", "v2").orElseThrow());
    }

    @Test
    public void listsVersionsAscendingAndLatestIsHighest() {
        assertEquals(List.of("v1", "v2"), registry.versions("greeter"));
        assertEquals("v2", registry.latestVersion("greeter").orElseThrow());
    }

    @Test
    public void unknownPromptOrVersionIsEmpty() {
        assertTrue(registry.content("no-such-prompt", "v1").isEmpty());
        assertTrue(registry.content("greeter", "v9").isEmpty());
        assertTrue(registry.versions("no-such-prompt").isEmpty());
        assertTrue(registry.latestVersion("no-such-prompt").isEmpty());
    }

    @Test
    public void rejectsInvalidNamesAndVersions() {
        assertThrows(IllegalArgumentException.class, () -> registry.content("../evil", "v1"));
        assertThrows(IllegalArgumentException.class, () -> registry.content("a/b", "v1"));
        assertThrows(IllegalArgumentException.class, () -> registry.content("greeter", "1"));
        assertThrows(IllegalArgumentException.class, () -> registry.content("greeter", "v1/../v2"));
        assertThrows(IllegalArgumentException.class, () -> registry.versions(".."));
    }

    @Test
    public void classpathIntegritySidecarMismatchFailsClosed() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> registry.content("tampered", "v1"));
        assertTrue(thrown.getMessage().contains("INTEGRITY FAILURE"), thrown.getMessage());
    }

    @Test
    public void classpathIntegritySidecarMatchResolves() {
        assertEquals("Verified prompt body.", registry.content("verified", "v1").orElseThrow());
    }

    @Test
    public void diskTierOverridesClasspathAndAllowsSparseVersions(@TempDir Path dir) throws Exception {
        var greeter = Files.createDirectories(dir.resolve("greeter"));
        Files.writeString(greeter.resolve("v1.md"), "Disk override v1.", StandardCharsets.UTF_8);
        Files.writeString(greeter.resolve("v5.md"), "Disk-only v5.", StandardCharsets.UTF_8);
        System.setProperty(FilePromptRegistry.PROMPT_DIR_PROPERTY, dir.toString());

        assertEquals("Disk override v1.", registry.content("greeter", "v1").orElseThrow());
        assertEquals("Greeter prompt v2.", registry.content("greeter", "v2").orElseThrow());
        assertEquals(List.of("v1", "v2", "v5"), registry.versions("greeter"));
        assertEquals("v5", registry.latestVersion("greeter").orElseThrow());
    }

    @Test
    public void diskIntegritySidecarMismatchFailsClosed(@TempDir Path dir) throws Exception {
        var name = Files.createDirectories(dir.resolve("audited"));
        Files.writeString(name.resolve("v1.md"), "Disk body.", StandardCharsets.UTF_8);
        Files.writeString(name.resolve("v1.md.sha256"), "f".repeat(64), StandardCharsets.UTF_8);
        System.setProperty(FilePromptRegistry.PROMPT_DIR_PROPERTY, dir.toString());

        var thrown = assertThrows(IllegalStateException.class,
                () -> registry.content("audited", "v1"));
        assertTrue(thrown.getMessage().contains("INTEGRITY FAILURE"), thrown.getMessage());
    }
}
