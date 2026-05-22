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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pin every {@code modules/<X>/SKILLCARD.yaml} manifest against
 * {@code .harness/capabilities.snapshot.json}. The skill cards are
 * machine-emitted by {@code scripts/regen-skillcards.sh}; this test catches
 * the cases where a runtime's pinned {@code expectedCapabilities()} changes
 * but the SKILLCARD is not regenerated. Mirrors the relationship between
 * {@link CapabilitySnapshotTest} and the snapshot itself — same staleness
 * detection pattern, one layer further out (snapshot &rarr; per-runtime card).
 *
 * <p>The skill-card shape is documented in {@code scripts/regen-skillcards.sh}
 * and inspired by the agentskills.io approach popularised by NVIDIA's verified
 * agent skills programme; this repository does not claim conformance with any
 * registered upstream schema version.</p>
 */
final class SkillCardSnapshotTest {

    private static final Pattern NAME_LINE =
            Pattern.compile("^name:\\s+(\\S+)\\s*$");
    private static final Pattern CAPABILITY_ITEM =
            Pattern.compile("^\\s{4}-\\s+([A-Z][A-Z0-9_]+)\\s*$");
    private static final Pattern CAPABILITY_COUNT =
            Pattern.compile("^\\s+count:\\s+(\\d+)\\s*$");
    private static final Pattern CAPABILITIES_HEADER =
            Pattern.compile("^capabilities:\\s*$");

    @Test
    void everyRuntimeInSnapshotHasFreshSkillCard() throws IOException {
        Path repoRoot = findRepoRoot();
        Path snapshotPath = repoRoot.resolve(".harness/capabilities.snapshot.json");
        assertTrue(Files.exists(snapshotPath),
                "Missing " + snapshotPath
                        + " — generate with ./scripts/regen-capability-snapshot.sh");

        JsonNode snapshot = new ObjectMapper().readTree(snapshotPath.toFile());

        // Build name → expected capabilities (alphabetical) from the snapshot.
        var snapshotByName = new java.util.LinkedHashMap<String, List<String>>();
        for (JsonNode item : snapshot.path("runtimes").path("items")) {
            String name = item.path("name").asString();
            List<String> caps = new ArrayList<>();
            for (JsonNode c : item.path("expected_capabilities")) {
                caps.add(c.asString());
            }
            snapshotByName.put(name, caps);
        }

        // Walk every SKILLCARD.yaml on disk and cross-check against the
        // snapshot. We deliberately do not derive the SKILLCARD location from
        // the snapshot's `module` field — for BuiltInAgentRuntime the snapshot
        // module is modules/ai-test (where the contract test lives) while the
        // SKILLCARD ships in modules/ai (where the runtime artifact lives).
        // Walking the cards is the source-of-truth-friendly direction.
        List<Path> cards;
        try (Stream<Path> walk = Files.walk(repoRoot.resolve("modules"), 2)) {
            cards = walk.filter(p -> p.getFileName().toString().equals("SKILLCARD.yaml"))
                    .sorted()
                    .toList();
        }

        if (cards.isEmpty()) {
            fail("No SKILLCARD.yaml manifests found under modules/ — "
                    + "generate with ./scripts/regen-skillcards.sh");
        }

        Set<String> visited = new HashSet<>();
        List<String> drifts = new ArrayList<>();

        for (Path card : cards) {
            SkillCard parsed = parseSkillCard(card);
            String relative = repoRoot.relativize(card).toString().replace('\\', '/');

            if (parsed.name == null || parsed.name.isBlank()) {
                drifts.add(relative + " has no `name:` field");
                continue;
            }

            List<String> expected = snapshotByName.get(parsed.name);
            if (expected == null) {
                drifts.add(relative + " names " + parsed.name
                        + " but no such runtime is pinned in the snapshot");
                continue;
            }
            visited.add(parsed.name);

            if (!expected.equals(parsed.capabilities)) {
                drifts.add(relative + " capabilities drift\n"
                        + "    snapshot expects: " + expected + "\n"
                        + "    card declares:    " + parsed.capabilities);
            }

            if (parsed.declaredCount != expected.size()) {
                drifts.add(relative + " capabilities.count=" + parsed.declaredCount
                        + " but capabilities.declared has " + expected.size() + " entries");
            }
        }

        Set<String> missing = new TreeSet<>(snapshotByName.keySet());
        missing.removeAll(visited);
        for (String name : missing) {
            drifts.add("No SKILLCARD.yaml found for runtime " + name
                    + " — regenerate with ./scripts/regen-skillcards.sh");
        }

        if (!drifts.isEmpty()) {
            fail("SKILLCARD drift detected:\n  " + String.join("\n  ", drifts)
                    + "\n\nRegenerate with: ./scripts/regen-skillcards.sh");
        }
    }

    /**
     * Structural schema gate — every SKILLCARD.yaml must carry the required
     * top-level keys with the right basic shape. The schema is hard-coded
     * here rather than in a separate JSON-Schema file because: (1) the
     * SKILLCARDs are generated by {@code regen-skillcards.sh} which is the
     * source of truth for the shape, and (2) the file count is small enough
     * that a dedicated schema library is overkill. The check catches the
     * cases the drift gate cannot: missing top-level key, wrong field name,
     * stale {@code spec} version, unrecognised {@code status}, missing
     * artifact coordinates.
     */
    @Test
    void everySkillCardConformsToShape() throws IOException {
        Path repoRoot = findRepoRoot();
        List<Path> cards;
        try (Stream<Path> walk = Files.walk(repoRoot.resolve("modules"), 2)) {
            cards = walk.filter(p -> p.getFileName().toString().equals("SKILLCARD.yaml"))
                    .sorted()
                    .toList();
        }
        if (cards.isEmpty()) {
            fail("No SKILLCARD.yaml manifests found under modules/");
        }

        // Required top-level keys + minimal shape assertions. Pattern values
        // are checked literally against the emitted YAML lines (the format
        // is machine-emitted and deterministic so a regex check is enough).
        Set<String> requiredTopLevel = new LinkedHashSet<>(List.of(
                "schema_version", "spec", "status", "name", "language",
                "description", "license", "artifact", "spi", "capabilities",
                "contract_test", "provenance", "signing"));
        Pattern schemaVersionRe = Pattern.compile("^schema_version:\\s+\\d+\\s*$");
        Pattern specRe = Pattern.compile("^spec:\\s+atmosphere/skillcard/v\\d+\\s*$");
        Pattern statusRe = Pattern.compile("^status:\\s+(unsigned|signed)\\s*$");
        Pattern languageRe = Pattern.compile("^language:\\s+(java|kotlin)\\s*$");
        Pattern groupIdRe = Pattern.compile("^\\s+group_id:\\s+org\\.atmosphere\\s*$");
        Pattern artifactIdRe = Pattern.compile("^\\s+artifact_id:\\s+atmosphere-[a-z0-9-]+\\s*$");
        Pattern licenseSpdxRe = Pattern.compile("^\\s+spdx:\\s+Apache-2\\.0\\s*$");
        Pattern signingStatusRe = Pattern.compile("^\\s+status:\\s+(unsigned|signed)\\s*$");
        Pattern signingEnvelopeRe = Pattern.compile("^\\s+envelope:\\s+openssf-model-signing/v\\d+\\s*$");
        Pattern signingFileRe = Pattern.compile("^\\s+signature_file:\\s+SKILLCARD\\.yaml\\.sig\\s*$");

        List<String> errors = new ArrayList<>();
        for (Path card : cards) {
            String relative = repoRoot.relativize(card).toString().replace('\\', '/');
            List<String> lines = Files.readAllLines(card);

            // Top-level keys: a line starting at column 0 with `<key>:`.
            Set<String> seen = new HashSet<>();
            for (String line : lines) {
                if (line.startsWith("#") || line.isBlank()) continue;
                int colon = line.indexOf(':');
                if (colon > 0 && !Character.isWhitespace(line.charAt(0))) {
                    seen.add(line.substring(0, colon));
                }
            }
            for (String required : requiredTopLevel) {
                if (!seen.contains(required)) {
                    errors.add(relative + " missing required top-level key: " + required);
                }
            }

            assertLineMatches(relative, lines, schemaVersionRe, "schema_version:", errors);
            assertLineMatches(relative, lines, specRe, "spec:", errors);
            assertLineMatches(relative, lines, statusRe, "status:", errors);
            assertLineMatches(relative, lines, languageRe, "language:", errors);
            assertLineMatches(relative, lines, groupIdRe, "  group_id:", errors);
            assertLineMatches(relative, lines, artifactIdRe, "  artifact_id:", errors);
            assertLineMatches(relative, lines, licenseSpdxRe, "  spdx:", errors);
            // Card carries a top-level `status:` AND a nested `signing.status:` —
            // both must be recognised values.
            long signingStatusMatches = lines.stream()
                    .filter(signingStatusRe.asPredicate()).count();
            if (signingStatusMatches == 0) {
                errors.add(relative + " missing `  status: unsigned|signed` under signing block");
            }
            assertLineMatches(relative, lines, signingEnvelopeRe,
                    "  envelope: openssf-model-signing/v1", errors);
            assertLineMatches(relative, lines, signingFileRe,
                    "  signature_file: SKILLCARD.yaml.sig", errors);
        }

        if (!errors.isEmpty()) {
            fail("SKILLCARD shape violations:\n  " + String.join("\n  ", errors)
                    + "\n\nRegenerate or fix scripts/regen-skillcards.sh emission.");
        }
    }

    private static void assertLineMatches(String file, List<String> lines, Pattern re,
                                          String hint, List<String> errors) {
        for (String line : lines) {
            if (re.matcher(line).matches()) {
                return;
            }
        }
        errors.add(file + " missing line matching " + re.pattern()
                + " (expected near `" + hint + "`)");
    }

    /**
     * When {@code modules/<X>/SKILLCARD.yaml.sig} exists on disk, verify
     * it against its card using the OpenSSF Model Signing CLI
     * ({@code model_signing verify}). Cards on {@code main} are
     * unsigned by design — signing happens in
     * {@code .github/workflows/sign-skillcards.yml} on tag push and the
     * signatures land on the GitHub release + as workflow artifacts —
     * so a missing {@code .sig} is not a failure here. A {@code .sig}
     * that exists but fails verification IS a failure: it means a card
     * was modified after signing or the signature file is corrupt.
     *
     * <p>The verifier is the {@code model_signing} CLI rather than
     * sigstore-java because (a) the sig format is OpenSSF Model
     * Signing's DSSE + in-toto envelope, which model_signing
     * understands natively, and (b) we already shell out to Python in
     * sister scripts ({@code validate-capability-claims.sh}). If
     * Python or model_signing is not installed, the test skips with an
     * Assumptions message so the dev loop is not blocked by missing
     * tooling.</p>
     */
    @Test
    void everySignedSkillCardVerifies() throws IOException, InterruptedException {
        Path repoRoot = findRepoRoot();
        List<Path> cards;
        try (Stream<Path> walk = Files.walk(repoRoot.resolve("modules"), 2)) {
            cards = walk.filter(p -> p.getFileName().toString().equals("SKILLCARD.yaml"))
                    .sorted()
                    .toList();
        }

        List<Path> signed = new ArrayList<>();
        for (Path card : cards) {
            Path sig = card.resolveSibling("SKILLCARD.yaml.sig");
            if (Files.exists(sig)) {
                signed.add(card);
            }
        }
        if (signed.isEmpty()) {
            // No cards have a .sig on this checkout — that's the normal
            // state on main. The CI workflow signs on tag push.
            return;
        }

        // model_signing is a Python CLI. If it isn't installed in the test
        // environment, skip rather than fail — the gate that matters is
        // the CI workflow that runs `model_signing verify` against the
        // freshly-signed artifacts on tag push.
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-m", "model_signing", "--version")
                    .redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "model_signing CLI not available; install with `pip install model-signing`");
                return;
            }
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "model_signing CLI not available: " + e.getMessage());
            return;
        }

        // Identity / identity-provider pin: signed-by-Atmosphere CI via
        // sigstore keyless. Downstream consumers verifying our cards
        // would use the same pin. Read from env var so this stays
        // configurable for ad-hoc verification (e.g. with a local
        // signing identity in dev or with a fork's identity in CI
        // shadow runs).
        String identity = System.getenv().getOrDefault("ATMOSPHERE_SKILLCARD_IDENTITY",
                "https://github.com/Atmosphere/atmosphere/.github/workflows/sign-skillcards.yml");
        String identityProvider = System.getenv().getOrDefault(
                "ATMOSPHERE_SKILLCARD_IDENTITY_PROVIDER",
                "https://token.actions.githubusercontent.com");

        List<String> failures = new ArrayList<>();
        for (Path card : signed) {
            Path sig = card.resolveSibling("SKILLCARD.yaml.sig");
            ProcessBuilder pb = new ProcessBuilder(
                    "python3", "-m", "model_signing", "verify", "sigstore",
                    "--signature", sig.toString(),
                    "--identity", identity,
                    "--identity_provider", identityProvider,
                    card.toString())
                    .redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                failures.add(repoRoot.relativize(card) + ": verify timed out");
                continue;
            }
            if (p.exitValue() != 0) {
                String out = new String(p.getInputStream().readAllBytes());
                failures.add(repoRoot.relativize(card) + ": " + out.lines().findFirst().orElse(""));
            }
        }

        if (!failures.isEmpty()) {
            fail("OMS signature verification failed for " + failures.size()
                    + " signed card(s):\n  " + String.join("\n  ", failures)
                    + "\n\nIf a card was modified after signing, re-run the sign workflow."
                    + "\nIf running locally with a non-Atmosphere identity, set"
                    + " ATMOSPHERE_SKILLCARD_IDENTITY and ATMOSPHERE_SKILLCARD_IDENTITY_PROVIDER.");
        }
    }

    private static SkillCard parseSkillCard(Path card) throws IOException {
        SkillCard out = new SkillCard();
        boolean inCapabilitiesBlock = false;
        boolean inDeclaredList = false;

        for (String line : Files.readAllLines(card)) {
            Matcher nameMatch = NAME_LINE.matcher(line);
            if (nameMatch.matches()) {
                out.name = nameMatch.group(1);
                continue;
            }

            if (CAPABILITIES_HEADER.matcher(line).matches()) {
                inCapabilitiesBlock = true;
                continue;
            }

            if (inCapabilitiesBlock) {
                Matcher countMatch = CAPABILITY_COUNT.matcher(line);
                if (countMatch.matches()) {
                    out.declaredCount = Integer.parseInt(countMatch.group(1));
                    continue;
                }
                if (line.matches("^\\s+declared:\\s*$")) {
                    inDeclaredList = true;
                    continue;
                }
                if (inDeclaredList) {
                    Matcher item = CAPABILITY_ITEM.matcher(line);
                    if (item.matches()) {
                        out.capabilities.add(item.group(1));
                        continue;
                    }
                    // First non-list line after declared: closes the block.
                    if (!line.isBlank()) {
                        inCapabilitiesBlock = false;
                        inDeclaredList = false;
                    }
                }
            }
        }
        return out;
    }

    private static Path findRepoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            if (Files.exists(p.resolve("mvnw")) && Files.exists(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException(
                "Could not locate atmosphere repo root (mvnw + pom.xml) from "
                        + System.getProperty("user.dir"));
    }

    private static final class SkillCard {
        String name;
        int declaredCount = -1;
        final List<String> capabilities = new ArrayList<>();
    }
}
