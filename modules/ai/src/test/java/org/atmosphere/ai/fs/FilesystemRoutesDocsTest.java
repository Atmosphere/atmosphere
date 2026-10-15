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
package org.atmosphere.ai.fs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate: a README that tells readers how to configure composite filesystem
 * routing names the key {@link AgentFileSystemProvider} actually reads. A
 * sample once documented {@code atmosphere.ai.filesystem.routes}, which nothing
 * reads, so the routes it described were silently ignored.
 */
class FilesystemRoutesDocsTest {

    private static final Pattern ROUTES_KEY =
            Pattern.compile("[A-Za-z0-9_.-]*atmosphere\\.ai\\.filesystem\\.routes");

    @Test
    void everyDocumentedRoutesKeyIsTheOneTheProviderReads() throws IOException {
        var root = Path.of("../..").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root.resolve("samples")), "repository root: " + root);
        var offenders = new ArrayList<String>();
        var mentions = 0;
        for (var readme : readmes(root)) {
            var lines = Files.readAllLines(readme);
            for (int i = 0; i < lines.size(); i++) {
                var m = ROUTES_KEY.matcher(lines.get(i));
                while (m.find()) {
                    mentions++;
                    if (!m.group().equals(AgentFileSystemProvider.FILESYSTEM_ROUTES_PROPERTY)) {
                        offenders.add(root.relativize(readme) + ":" + (i + 1) + " " + m.group());
                    }
                }
            }
        }
        assertTrue(mentions > 0, "expected at least one README to document the routes key");
        assertEquals(List.of(), offenders,
                "READMEs must name " + AgentFileSystemProvider.FILESYSTEM_ROUTES_PROPERTY);
    }

    private static List<Path> readmes(Path root) throws IOException {
        var found = new ArrayList<Path>();
        for (var dir : List.of(root.resolve("samples"), root.resolve("modules"))) {
            try (var files = Files.walk(dir)) {
                files.filter(f -> f.getFileName().toString().equals("README.md"))
                        .filter(f -> !f.toString().contains("/node_modules/")
                                && !f.toString().contains("/target/"))
                        .forEach(found::add);
            }
        }
        return found;
    }
}
