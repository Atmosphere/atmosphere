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
package org.atmosphere.ai.test;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pin the canonical snapshot of {@link org.atmosphere.ai.AiCapability} entries
 * and per-runtime expected-capability sets against
 * {@code .harness/capabilities.snapshot.json}, and assert that count claims in
 * {@code modules/ai/README.md} agree with the snapshot.
 *
 * <p>The per-runtime contract tests already pin each runtime's
 * {@code capabilities()} against an {@code expectedCapabilities()} declaration —
 * this test closes the next layer of drift: the aggregate counts and the prose
 * claims that quote them. Mirrors {@code scripts/validate-capability-claims.sh}
 * so the gate runs in both {@code mvn test} and pre-push.</p>
 */
final class CapabilitySnapshotTest {

    private static final Pattern ENUM_CONSTANT =
            Pattern.compile("^\\s*([A-Z][A-Z0-9_]+)\\s*([,;]|$)");
    private static final Pattern AI_CAPABILITY_REF =
            Pattern.compile("AiCapability\\.([A-Z][A-Z0-9_]+)");
    private static final Pattern ALL_N_RUNTIMES =
            Pattern.compile("\\bAll (\\d+) runtimes?\\b");

    @Test
    void snapshotMatchesSourceOfTruth() throws IOException {
        Path repoRoot = findRepoRoot();
        Path snapshotPath = repoRoot.resolve(".harness/capabilities.snapshot.json");
        assertTrue(Files.exists(snapshotPath),
                "Missing " + snapshotPath
                        + " — generate with ./scripts/regen-capability-snapshot.sh");

        JsonNode snapshot = new ObjectMapper().readTree(snapshotPath.toFile());

        List<String> liveCapabilities = parseEnumConstants(
                repoRoot.resolve("modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java"));
        List<String> snapshotCapabilities = jsonArrayAsStrings(
                snapshot.path("capabilities").path("names"));
        assertEquals(liveCapabilities, snapshotCapabilities,
                "Capability list drift between AiCapability.java and snapshot — "
                        + "regenerate with ./scripts/regen-capability-snapshot.sh");
        assertEquals(liveCapabilities.size(),
                snapshot.path("capabilities").path("count").asInt(),
                "capabilities.count out of sync with capabilities.names");

        List<RuntimeEntry> liveRuntimes = parseAllRuntimes(repoRoot);
        List<RuntimeEntry> snapshotRuntimes = parseSnapshotRuntimes(
                snapshot.path("runtimes").path("items"));
        assertEquals(liveRuntimes, snapshotRuntimes,
                "Runtime entries drift between contract tests and snapshot — "
                        + "regenerate with ./scripts/regen-capability-snapshot.sh");
        assertEquals(liveRuntimes.size(),
                snapshot.path("runtimes").path("count").asInt(),
                "runtimes.count out of sync with runtimes.items");
    }

    @Test
    void readmeCountClaimsMatchSnapshot() throws IOException {
        Path repoRoot = findRepoRoot();
        Path snapshotPath = repoRoot.resolve(".harness/capabilities.snapshot.json");
        Path readme = repoRoot.resolve("modules/ai/README.md");
        assertTrue(Files.exists(readme), "Missing " + readme);

        JsonNode snapshot = new ObjectMapper().readTree(snapshotPath.toFile());
        int runtimeCount = snapshot.path("runtimes").path("count").asInt();

        List<String> lines = Files.readAllLines(readme);
        List<String> drifts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = ALL_N_RUNTIMES.matcher(lines.get(i));
            while (m.find()) {
                int claimed = Integer.parseInt(m.group(1));
                if (claimed != runtimeCount) {
                    drifts.add("modules/ai/README.md:" + (i + 1)
                            + " claims 'All " + claimed + " runtimes' but snapshot has "
                            + runtimeCount + "  —  line: " + lines.get(i).strip());
                }
            }
        }
        if (!drifts.isEmpty()) {
            fail("README count claim drift:\n  " + String.join("\n  ", drifts)
                    + "\n\nFix the prose, or regenerate the snapshot if the source of truth"
                    + " changed: ./scripts/regen-capability-snapshot.sh");
        }
    }

    private static Path findRepoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            // mvnw is only present at the multi-module root; child poms
            // inherit `<artifactId>atmosphere-project</artifactId>` as their
            // parent, so the artifactId marker is ambiguous.
            if (Files.exists(p.resolve("mvnw")) && Files.exists(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException(
                "Could not locate atmosphere repo root (mvnw + pom.xml) from "
                        + System.getProperty("user.dir"));
    }

    private static List<String> parseEnumConstants(Path enumSource) throws IOException {
        boolean inEnum = false;
        TreeSet<String> constants = new TreeSet<>();
        for (String line : Files.readAllLines(enumSource)) {
            if (line.startsWith("public enum AiCapability")) {
                inEnum = true;
                continue;
            }
            if (!inEnum) {
                continue;
            }
            if (line.startsWith("}")) {
                break;
            }
            Matcher m = ENUM_CONSTANT.matcher(line);
            if (m.find()) {
                constants.add(m.group(1));
            }
        }
        return new ArrayList<>(constants);
    }

    private static List<RuntimeEntry> parseAllRuntimes(Path repoRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot.resolve("modules"))) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return (n.endsWith("RuntimeContractTest.java")
                                || n.endsWith("RuntimeContractTest.kt"))
                                && !n.startsWith("Abstract")
                                && !n.contains("Embedding");
                    })
                    .map(p -> toRuntimeEntry(repoRoot, p))
                    .sorted((a, b) -> a.contractTest.compareTo(b.contractTest))
                    .collect(Collectors.toList());
        }
    }

    private static RuntimeEntry toRuntimeEntry(Path repoRoot, Path testFile) {
        String fileName = testFile.getFileName().toString();
        String testClass = fileName.replaceFirst("\\.(java|kt)$", "");
        String runtimeName = testClass.replaceFirst("RuntimeContractTest$", "") + "AgentRuntime";
        String relTest = repoRoot.relativize(testFile).toString().replace('\\', '/');
        // modules/<X>/src/test/...  →  modules/<X>
        String modulePath = relTest.split("/src/test/")[0];
        String language = fileName.endsWith(".kt") ? "kotlin" : "java";
        TreeSet<String> caps = new TreeSet<>();
        try {
            String body = Files.readString(testFile);
            int start = body.indexOf("expectedCapabilities");
            if (start < 0) {
                throw new IllegalStateException(
                        testFile + " has no expectedCapabilities()");
            }
            Matcher m = AI_CAPABILITY_REF.matcher(body.substring(start));
            while (m.find()) {
                caps.add(m.group(1));
            }
        } catch (IOException e) {
            throw new IllegalStateException("read " + testFile, e);
        }
        return new RuntimeEntry(runtimeName, modulePath, language, relTest, new ArrayList<>(caps));
    }

    private static List<RuntimeEntry> parseSnapshotRuntimes(JsonNode items) {
        List<RuntimeEntry> out = new ArrayList<>();
        for (JsonNode n : items) {
            out.add(new RuntimeEntry(
                    n.path("name").asString(),
                    n.path("module").asString(),
                    n.path("language").asString(),
                    n.path("contract_test").asString(),
                    jsonArrayAsStrings(n.path("expected_capabilities"))));
        }
        out.sort((a, b) -> a.contractTest.compareTo(b.contractTest));
        return out;
    }

    private static List<String> jsonArrayAsStrings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asString());
        }
        return out;
    }

    private record RuntimeEntry(String name,
                                String module,
                                String language,
                                String contractTest,
                                List<String> expectedCapabilities) {
    }
}
