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
package org.atmosphere.ai.governance.compliance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirror of {@code OwaspMatrixPinTest} for the regulatory-framework
 * matrices (EU AI Act / HIPAA / SOC2). Every evidence class referenced
 * in {@link ComplianceMatrix#MATRICES} must resolve to a real source
 * file on disk; renaming / deleting an evidence primitive fails the
 * build before the matrix can drift silently.
 */
class ComplianceMatrixPinTest {

    @Test
    void everyEvidenceClassExistsInRepoSource() throws IOException {
        var repoRoot = resolveRepoRoot();
        var missing = new ArrayList<String>();

        for (var entry : ComplianceMatrix.MATRICES.entrySet()) {
            var framework = entry.getKey();
            for (var row : entry.getValue()) {
                for (var evidence : row.evidence()) {
                    if (!sourceExists(repoRoot, evidence.evidenceClass())) {
                        missing.add(framework + " / " + row.id()
                                + " — evidence class missing: " + evidence.evidenceClass());
                    }
                    if (!evidence.testClass().isBlank()
                            && !sourceExists(repoRoot, evidence.testClass())) {
                        missing.add(framework + " / " + row.id()
                                + " — test class missing: " + evidence.testClass());
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "ComplianceMatrix evidence drifted — update the matrix AND the renamed "
                        + "classes in the same commit:\n  " + String.join("\n  ", missing));
    }

    @Test
    void frameworksProduceExpectedRowCounts() {
        // Structural smoke test — lock row count per framework so a silent
        // row deletion surfaces in review.
        assertEquals(5, ComplianceMatrix.EU_AI_ACT.size(),
                "EU AI Act matrix must have 5 rows");
        assertEquals(5, ComplianceMatrix.HIPAA.size(),
                "HIPAA matrix must have 5 rows");
        assertEquals(5, ComplianceMatrix.SOC2.size(),
                "SOC2 matrix must have 5 rows");
    }

    @Test
    void everyFrameworkHasADisplayName() {
        for (var framework : ComplianceMatrix.Framework.values()) {
            assertTrue(framework.displayName() != null
                            && !framework.displayName().isBlank(),
                    framework + " must have a non-blank displayName");
        }
    }

    private static boolean sourceExists(Path repoRoot, String fullyQualifiedClassName) throws IOException {
        var relative = fullyQualifiedClassName.replace('.', '/') + ".java";
        try (Stream<Path> walk = Files.walk(repoRoot.resolve("modules"), 12)) {
            return walk.anyMatch(p -> p.toString().endsWith(relative));
        }
    }

    private static Path resolveRepoRoot() {
        var cwd = Path.of("").toAbsolutePath();
        var probe = cwd;
        while (probe != null) {
            if (Files.exists(probe.resolve("modules")) && Files.exists(probe.resolve("pom.xml"))) {
                return probe;
            }
            probe = probe.getParent();
        }
        return cwd;
    }
}
