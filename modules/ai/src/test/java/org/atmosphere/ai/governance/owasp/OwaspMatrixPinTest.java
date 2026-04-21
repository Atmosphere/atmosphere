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
package org.atmosphere.ai.governance.owasp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI gate that pins every {@link OwaspAgenticMatrix} evidence pointer to a
 * real class on disk. The test reads the repo sources directly (not the
 * compiled classpath) so the pin catches cross-module evidence too —
 * {@code atmosphere-admin} references classes that live in
 * {@code atmosphere-ai}, etc.
 *
 * <h2>What this test protects</h2>
 * Marketing / compliance copy will eventually cite this matrix. When
 * someone deletes or renames an evidence class, the matrix must fail the
 * build loudly rather than quietly keep the stale reference alive. That's
 * the v4 gist §4 Phase D 'organizational discipline' risk: the matrix is
 * a credibility liability when it drifts.
 */
class OwaspMatrixPinTest {

    @Test
    void everyEvidenceClassExistsInRepoSource() throws IOException {
        var repoRoot = resolveRepoRoot();
        var missing = new ArrayList<String>();

        for (var row : OwaspAgenticMatrix.MATRIX) {
            for (var evidence : row.evidence()) {
                if (!sourceExists(repoRoot, evidence.evidenceClass())) {
                    missing.add(row.id() + " — evidence class missing: " + evidence.evidenceClass());
                }
                if (!evidence.testClass().isBlank()
                        && !sourceExists(repoRoot, evidence.testClass())) {
                    missing.add(row.id() + " — test class missing: " + evidence.testClass());
                }
            }
        }

        if (!missing.isEmpty()) {
            throw new AssertionError(
                    "OWASP matrix evidence references classes that no longer exist. "
                            + "Either restore the class, update OwaspAgenticMatrix.MATRIX, or downgrade "
                            + "the row's coverage. See docs/governance-policy-plane.md.\n  "
                            + String.join("\n  ", missing));
        }
    }

    @Test
    void everyRowHasCoverageAndDescription() {
        for (var row : OwaspAgenticMatrix.MATRIX) {
            assertTrue(row.description() != null && !row.description().isBlank(),
                    "row " + row.id() + " is missing a description");
            assertTrue(row.coverage() != null, "row " + row.id() + " is missing coverage");
            // Every non-NOT_ADDRESSED row must ship at least one evidence pointer.
            if (row.coverage() != OwaspAgenticMatrix.Coverage.NOT_ADDRESSED) {
                assertTrue(!row.evidence().isEmpty(),
                        "row " + row.id() + " is " + row.coverage()
                                + " but has no evidence pointers");
            }
        }
    }

    @Test
    void idsAreStableAndOrdered() {
        var expected = List.of("A01", "A02", "A03", "A04", "A05",
                "A06", "A07", "A08", "A09", "A10");
        var actual = OwaspAgenticMatrix.MATRIX.stream().map(OwaspAgenticMatrix.Row::id).toList();
        assertEquals(expected, actual,
                "OWASP Top-10 ids must stay A01..A10 in order; reordering breaks doc links");
    }

    private static boolean sourceExists(Path repoRoot, String fullyQualifiedName) throws IOException {
        var suffix = fullyQualifiedName.replace('.', '/') + ".java";
        // Walk modules/ and samples/ once each; stop on the first hit.
        try (Stream<Path> modules = Files.walk(repoRoot.resolve("modules"), 20)) {
            if (modules.anyMatch(p -> p.endsWith(suffix))) return true;
        } catch (IOException e) {
            // modules dir may not exist in certain test layouts; fall through
        }
        try (Stream<Path> kotlin = Files.walk(repoRoot.resolve("modules"), 20)) {
            var ktSuffix = fullyQualifiedName.replace('.', '/') + ".kt";
            if (kotlin.anyMatch(p -> p.endsWith(ktSuffix))) return true;
        } catch (IOException e) {
            // skip
        }
        return false;
    }

    private static Path resolveRepoRoot() {
        var cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        var probe = cwd;
        while (probe != null && !Files.isDirectory(probe.resolve("modules"))) {
            probe = probe.getParent();
        }
        if (probe == null) {
            throw new IllegalStateException("could not locate repo root from cwd=" + cwd);
        }
        return probe;
    }
}
