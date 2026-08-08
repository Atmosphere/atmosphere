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
package org.atmosphere.ai.state.seal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring-truth gate for {@link AgentStateIntegrity}.
 *
 * <p>The primitive's production consumer is {@link AgentStateSealer}, which
 * {@code FileSystemAgentState} invokes on every load and save once the
 * opt-in state seal is enabled. {@code modules/ai/README.md} documents that
 * wiring; this test keeps the documentation honest in <b>both</b>
 * directions by walking {@code src/main}:</p>
 *
 * <ul>
 *   <li>if the consumer chain is ever unhooked (the sealer stops calling
 *       the primitive, or the file-backed state stops calling the sealer),
 *       the build breaks — the README would be advertising a control that
 *       no longer runs (Correctness Invariant #5);</li>
 *   <li>if a NEW production consumer appears, the build breaks until it is
 *       added to the expected set here and reflected in the README (and in
 *       any governance-matrix evidence) in the same commit.</li>
 * </ul>
 *
 * <p>Successor of the pre-wiring version of this test (then in
 * {@code modules/coordinator}), which pinned the primitive's <i>absence</i>
 * of consumers after the 4.0.59 regression cited it as OWASP A03
 * {@code COVERED} with zero real callers. The control is opt-in, so it
 * still belongs in that row's notes — not its evidence list.</p>
 */
class AgentStateIntegrityWiringPinTest {

    private static final String CLASS_NAME = "AgentStateIntegrity";

    /**
     * The complete expected set of production consumers of the primitive.
     * Files named {@code AgentStateIntegrity.java} (the primitive itself and
     * the deprecated coordinator forwarder) are not consumers.
     */
    private static final List<String> EXPECTED_CONSUMERS = List.of(
            "modules/ai/src/main/java/org/atmosphere/ai/state/seal/AgentStateSealer.java");

    @Test
    void agentStateIntegrityIsConsumedByExactlyTheSealer() throws IOException {
        var repoRoot = resolveRepoRoot();
        var consumers = new TreeSet<String>();

        for (var file : productionSources(repoRoot)) {
            // The primitive's own file and the deprecated coordinator
            // forwarder carry the same file name; neither is a consumer.
            if (file.getFileName().toString().equals(CLASS_NAME + ".java")) {
                continue;
            }
            if (mentionsInCode(Files.readString(file), CLASS_NAME)) {
                consumers.add(repoRoot.relativize(file).toString().replace('\\', '/'));
            }
        }

        assertEquals(new TreeSet<>(EXPECTED_CONSUMERS), consumers, """
                The production consumer set of %s changed.

                Removed a consumer? modules/ai/README.md advertises opt-in state sealing
                as a wired control — unhooking the consumer makes that a false claim
                (Correctness Invariant #5). Re-wire it or fix the README in the SAME commit.
                Added a consumer? Add it to EXPECTED_CONSUMERS and update the README —
                and only cite OWASP A03 evidence for it if it is default-on and genuinely
                enforcing; an opt-in seam belongs in the row's notes.
                """.formatted(CLASS_NAME));
    }

    /**
     * The wiring chain's second link: the shipped {@code FileSystemAgentState}
     * must still call into {@link AgentStateSealer}. Without this, someone
     * could keep the sealer referencing the primitive while silently
     * unhooking the sealer from the file-backed state — the grep above would
     * stay green while the control stopped running.
     */
    @Test
    void fileSystemAgentStateStillInvokesTheSealer() throws IOException {
        var repoRoot = resolveRepoRoot();
        var stateFile = repoRoot.resolve(
                "modules/ai/src/main/java/org/atmosphere/ai/state/FileSystemAgentState.java");
        assertTrue(Files.isRegularFile(stateFile),
                "FileSystemAgentState.java moved — update this pin to follow it");
        assertTrue(mentionsInCode(Files.readString(stateFile), "AgentStateSealer"),
                "FileSystemAgentState no longer references AgentStateSealer in real code — "
                        + "the opt-in state seal is unhooked from the shipped path. Re-wire it "
                        + "or update modules/ai/README.md (and this test) in the SAME commit.");
    }

    /**
     * Sanity-check the detector itself: a comment or import mention must not
     * register as a consumer, but real code must. Without this, a regression
     * that made {@link #mentionsInCode} always return {@code false} would
     * let the consumer-set gate pass vacuously forever — and one that made
     * it always return {@code true} would count Javadoc citations as wiring.
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
