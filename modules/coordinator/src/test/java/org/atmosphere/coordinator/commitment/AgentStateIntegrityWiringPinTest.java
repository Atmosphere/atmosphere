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
package org.atmosphere.coordinator.commitment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Documentation-truth gate for {@link AgentStateIntegrity}.
 *
 * <p>{@code modules/ai/README.md} states that memory-snapshot sealing is
 * <b>not wired to a shipped path</b> and that this class is a standalone
 * utility. That sentence is only true while the class has no production
 * caller, so this test walks {@code src/main} and fails the build the moment
 * one appears — forcing whoever wires it to update the README (and any
 * governance-matrix evidence) in the same commit.</p>
 *
 * <p>This is the inverse of {@code EvidenceConsumerGrepPinTest}, which proves
 * cited primitives <i>do</i> have consumers. Both exist because the 4.0.59
 * regression cited this exact class as OWASP A03 {@code COVERED} while it had
 * zero real callers; a claim in either direction now breaks a test instead of
 * shipping.</p>
 */
class AgentStateIntegrityWiringPinTest {

    private static final String CLASS_NAME = "AgentStateIntegrity";

    @Test
    void agentStateIntegrityStillHasNoProductionConsumer() throws IOException {
        var repoRoot = resolveRepoRoot();
        var consumers = new ArrayList<String>();

        for (var file : productionSources(repoRoot)) {
            // The class's own file is not a consumer of itself.
            if (file.getFileName().toString().equals(CLASS_NAME + ".java")) {
                continue;
            }
            if (mentionsInCode(Files.readString(file), CLASS_NAME)) {
                consumers.add(repoRoot.relativize(file).toString());
            }
        }

        if (!consumers.isEmpty()) {
            fail("""
                    %s now has production consumer(s): %s

                    That is good news — but modules/ai/README.md currently states that
                    memory-snapshot sealing is "not wired to a shipped path" and that this
                    class is a "standalone seal/verify utility with no production caller".
                    Update that wording (and this test) in the SAME commit, and only claim
                    OWASP A03 coverage if the new consumer is default-on and genuinely
                    enforcing — an opt-in seam belongs in the row's notes, not its evidence.
                    """.formatted(CLASS_NAME, consumers));
        }
    }

    /**
     * Sanity-check the detector itself: a comment or import mention must not
     * register as a consumer, but real code must. Without this, a regression
     * that made {@link #mentionsInCode} always return {@code false} would let
     * the gate above pass vacuously forever.
     */
    @Test
    void detectorIgnoresCommentsAndImportsButSeesRealCode() {
        assertTrue(!mentionsInCode(" * seals via AgentStateIntegrity\n", CLASS_NAME),
                "a Javadoc continuation must not count as a consumer");
        assertTrue(!mentionsInCode("import org.atmosphere.x.AgentStateIntegrity;\n", CLASS_NAME),
                "an import must not count as a consumer");
        assertTrue(!mentionsInCode("int x = 1; // AgentStateIntegrity\n", CLASS_NAME),
                "a trailing line comment must not count as a consumer");
        assertTrue(mentionsInCode("    var s = AgentStateIntegrity.generate();\n", CLASS_NAME),
                "a real code reference must count as a consumer");
    }

    /** Strips imports and comment lines, then looks for a genuine code mention. */
    private static boolean mentionsInCode(String source, String needle) {
        var inBlockComment = false;
        for (var rawLine : source.split("\n", -1)) {
            var line = rawLine.strip();
            if (inBlockComment) {
                if (line.contains("*/")) {
                    inBlockComment = false;
                    line = line.substring(line.indexOf("*/") + 2).strip();
                } else {
                    continue;
                }
            }
            if (line.startsWith("/*")) {
                if (!line.contains("*/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("import ")) {
                continue;
            }
            var codeOnly = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            if (codeOnly.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> productionSources(Path repoRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(repoRoot.resolve("modules"))) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList();
        }
    }

    /** Walk up from the working directory until the reactor root is found. */
    private static Path resolveRepoRoot() {
        var dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("modules"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("modules/coordinator"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate the reactor root from "
                + Path.of("").toAbsolutePath());
    }
}
