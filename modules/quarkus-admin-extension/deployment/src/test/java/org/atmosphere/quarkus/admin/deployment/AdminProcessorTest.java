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
package org.atmosphere.quarkus.admin.deployment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins that every console asset actually shipped is reachable in native image.
 *
 * <p><b>Why this test is written against the filesystem.</b> Its predecessor
 * asserted that the build step's resource list contained two specific hashed
 * filenames — which is the registration checked against itself. It passed
 * while both names had gone stale, so in native image the console's only script
 * and stylesheet were pruned and the SPA served a blank page. A gate that walks
 * its own artifact certifies whatever the artifact happens to say; this one
 * compares the registration to the bundle on disk, so a Vite rebuild that
 * restamps every content hash cannot slip past it.</p>
 */
class AdminProcessorTest {

    private static final String CONSOLE_ASSETS =
            "META-INF/resources/atmosphere/console/assets";

    /** The runtime module owns the packaged bundle; this test lives in deployment. */
    private static Path assetsDir() {
        var dir = Path.of("..", "runtime", "src", "main", "resources", CONSOLE_ASSETS)
                .normalize();
        if (!Files.isDirectory(dir)) {
            fail("Console asset directory not found at " + dir.toAbsolutePath()
                    + " — if the bundle moved, fix this test rather than deleting it: "
                    + "it is the only thing standing between a Vite rebuild and a "
                    + "blank console in native image.");
        }
        return dir;
    }

    private static List<String> packagedAssets() throws IOException {
        try (Stream<Path> files = Files.list(assetsDir())) {
            var names = new ArrayList<String>();
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .forEach(names::add);
            return names;
        }
    }

    private static Match registration() {
        var literals = new ArrayList<String>();
        var globs = new ArrayList<String>();
        new AdminProcessor().registerConsoleResources(
                item -> literals.addAll(item.getResources()),
                item -> item.getIncludePatterns().forEach(globs::add));
        return new Match(literals, globs);
    }

    private record Match(List<String> literals, List<String> globs) {
        boolean covers(String resource) {
            if (literals.contains(resource)) {
                return true;
            }
            // Quarkus converts includeGlob(...) to a regex before storing it, so
            // getIncludePatterns() hands back regex source, not glob source.
            return globs.stream().anyMatch(p -> java.util.regex.Pattern.matches(p, resource));
        }
    }

    @Test
    void everyPackagedConsoleAssetIsRegisteredForNativeImage() throws IOException {
        var assets = packagedAssets();
        assertFalse(assets.isEmpty(),
                "no console assets found — the bundle is missing, so this test would "
                        + "otherwise pass vacuously");

        var registration = registration();
        var unregistered = assets.stream()
                .map(name -> CONSOLE_ASSETS + "/" + name)
                .filter(resource -> !registration.covers(resource))
                .toList();

        assertTrue(unregistered.isEmpty(),
                "these packaged console assets would be pruned from the native image: "
                        + unregistered + " — register them by pattern, not by hashed "
                        + "filename, or the next SPA rebuild breaks the console again");
    }

    @Test
    void theEntryPointsAreRegisteredLiterally() {
        var registration = registration();

        assertTrue(registration.literals().contains("META-INF/resources/admin/index.html"));
        assertTrue(registration.literals()
                .contains("META-INF/resources/atmosphere/console/index.html"));
    }

    /**
     * The matcher itself must bite. A hashed asset name that no pattern covers has
     * to read as unregistered — otherwise this whole test would pass on an empty
     * registration, which is precisely how its predecessor failed.
     */
    @Test
    void anAssetOutsideEveryPatternReadsAsUnregistered() {
        var registration = registration();

        assertFalse(registration.covers("META-INF/resources/somewhere/else/index-XXXX.js"),
                "a resource outside the registered tree must not be reported as covered");
        assertTrue(registration.covers(CONSOLE_ASSETS + "/index-anyNewHash.js"),
                "any asset under the console bundle must be covered regardless of its hash");
    }
}
