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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.governance.compliance.ComplianceMatrix;
import org.atmosphere.ai.governance.owasp.OwaspAgenticMatrix;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runtime-verified contract: every non-blank {@code consumerGrepPattern}
 * in {@link OwaspAgenticMatrix} + {@link ComplianceMatrix} must match at
 * least one <b>production</b> source file (i.e. {@code src/main/**}
 * under {@code modules/} or {@code samples/}), and that file must NOT be
 * the evidence class itself.
 *
 * <p>Enforces "production-consumer grep-verified" compliance coverage.
 * Before this gate landed, the {@code consumerGrepPattern} field was
 * metadata only — a row could claim {@code COVERED} while the caller it
 * pointed at had been deleted, and nothing failed the build. That's the
 * credibility liability we can't afford: if a governance row says
 * "covered by X" and X is actually dead code, the matrix becomes noise.</p>
 *
 * <p>Why the evidence class is excluded from the search: the whole point
 * of "consumer grep" is to prove a <i>consumer</i> exists. A class
 * containing its own name is trivially true and proves nothing about
 * whether the SPI has real callers.</p>
 */
class EvidenceConsumerGrepPinTest {

    @Test
    void everyOwaspConsumerGrepFindsAProductionCaller() throws IOException {
        var repoRoot = resolveRepoRoot();
        var productionFiles = walkProductionSources(repoRoot);

        var failures = new ArrayList<String>();
        for (var row : OwaspAgenticMatrix.MATRIX) {
            for (var evidence : row.evidence()) {
                verifyConsumer(row.id(), evidence.evidenceClass(),
                        evidence.consumerGrepPattern(), productionFiles, failures);
            }
        }
        failIfAny(failures, "OWASP");
    }

    @Test
    void everyComplianceConsumerGrepFindsAProductionCaller() throws IOException {
        var repoRoot = resolveRepoRoot();
        var productionFiles = walkProductionSources(repoRoot);

        var failures = new ArrayList<String>();
        for (var framework : ComplianceMatrix.MATRICES.entrySet()) {
            for (var row : framework.getValue()) {
                for (var evidence : row.evidence()) {
                    verifyConsumer(framework.getKey().name() + "/" + row.id(),
                            evidence.evidenceClass(),
                            evidence.consumerGrepPattern(),
                            productionFiles, failures);
                }
            }
        }
        failIfAny(failures, "Compliance");
    }

    private static void verifyConsumer(String rowKey, String evidenceClass, String pattern,
                                        List<Path> productionFiles, List<String> failures) {
        if (pattern == null || pattern.isBlank()) {
            // Intentional skip — some evidence rows carry empty patterns
            // because the evidence class is itself the CI gate (e.g.
            // SampleAgentScopeLintTest), not something with external callers.
            return;
        }
        var evidenceFile = classToRelativePath(evidenceClass);
        var hitFound = false;
        for (var file : productionFiles) {
            var rel = file.toString();
            // Skip the evidence file itself — self-match is not a consumer.
            if (evidenceFile != null && rel.endsWith(evidenceFile)) {
                continue;
            }
            try {
                var content = Files.readString(file);
                if (content.contains(pattern)) {
                    hitFound = true;
                    break;
                }
            } catch (IOException e) {
                // Treat unreadable source as a miss — surfaces encoding
                // breakage rather than silently passing.
            }
        }
        if (!hitFound) {
            failures.add(rowKey + " — pattern '" + pattern
                    + "' (evidence class " + evidenceClass
                    + ") has zero production consumers");
        }
    }

    private static void failIfAny(List<String> failures, String matrixName) {
        if (!failures.isEmpty()) {
            throw new AssertionError(
                    matrixName + " matrix has rows claiming coverage with no production "
                            + "consumer. Either restore the caller, update the pattern, "
                            + "or downgrade the row's coverage.\n  "
                            + String.join("\n  ", failures));
        }
    }

    /** Walk every .java / .kt file under {@code modules/**} and {@code samples/**} src/main. */
    private static List<Path> walkProductionSources(Path repoRoot) throws IOException {
        var roots = List.of(repoRoot.resolve("modules"), repoRoot.resolve("samples"));
        var files = new ArrayList<Path>();
        var seen = new HashSet<String>();
        for (var root : roots) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(EvidenceConsumerGrepPinTest::isJavaOrKotlin)
                        .filter(EvidenceConsumerGrepPinTest::isProductionPath)
                        .forEach(p -> {
                            var key = p.toAbsolutePath().toString();
                            if (seen.add(key)) files.add(p);
                        });
            }
        }
        return files;
    }

    private static boolean isJavaOrKotlin(Path p) {
        var name = p.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    /**
     * "Production" = under a {@code src/main/} directory and NOT under
     * {@code target/}, {@code build/}, {@code .git/}, or {@code node_modules}.
     */
    private static boolean isProductionPath(Path p) {
        var parts = Set.of();
        var str = p.toString();
        if (!str.contains("/src/main/")) return false;
        if (str.contains("/target/")) return false;
        if (str.contains("/build/")) return false;
        if (str.contains("/.git/")) return false;
        if (str.contains("/node_modules/")) return false;
        return !parts.contains(str);
    }

    /** {@code a.b.Foo} → {@code a/b/Foo.java}. Returns null for blank input. */
    private static String classToRelativePath(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return null;
        return fqcn.replace('.', '/') + ".java";
    }

    private static Path resolveRepoRoot() {
        // Tests run with CWD = modules/ai; repo root is two levels up.
        var candidate = Path.of(System.getProperty("user.dir"));
        while (candidate != null
                && !Files.exists(candidate.resolve("modules"))
                && !Files.exists(candidate.resolve("pom.xml"))) {
            candidate = candidate.getParent();
        }
        // Walk further up if we landed on a nested pom.xml without the
        // modules/ sibling (Maven reactor oddity).
        while (candidate != null && !Files.exists(candidate.resolve("modules"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "couldn't locate repo root (looking for a directory containing modules/)");
        }
        return candidate;
    }
}
