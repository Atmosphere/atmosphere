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
package org.atmosphere.ai.governance.acs;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural coverage of {@link AcsExtendsResolver} against upstream's own
 * extends fixtures. The trees under
 * {@code modules/ai/src/test/resources/ms-acs-extends/} are verbatim copies
 * of {@code policy-engine/core/tests/fixtures/extends/} in
 * microsoft/agent-governance-toolkit — they are <b>behaviour fixtures pinned
 * by the assertions in this test</b> and deliberately NOT added to the
 * weekly {@code ms-yaml-conformance} byte-diff workflow (which tracks the
 * manifest contract fixtures under {@code ms-acs/} only).
 *
 * <p>Confinement, cycle and missing-file paths need real files on disk —
 * {@code toRealPath} cannot canonicalize classpath resources — so every test
 * copies the fixture tree into a {@code @TempDir} first.</p>
 */
class AcsExtendsResolverTest {

    private static final List<String> FIXTURES = List.of(
            "ordered/base.yaml",
            "ordered/child.yaml",
            "ordered/nested/mid.yaml",
            "conflict/base.yaml",
            "conflict/intervention-point-conflict.yaml",
            "conflict/policy-conflict.yaml",
            "cycle/a.yaml",
            "cycle/b.yaml",
            "missing/child.yaml",
            "confinement/outside.yaml",
            "confinement/root/escape.yaml",
            "confinement/root/inside.yaml",
            "confinement/root/url.yaml",
            "confinement/root/subdir/base.yaml");

    // --- ordered merge ----------------------------------------------------

    @Test
    void orderedChainMergesDepthFirstAndAdditively(@TempDir Path dir) throws IOException {
        var child = copyFixtures(dir).resolve("ordered/child.yaml");

        var manifest = AcsExtendsResolver.resolve("yaml:" + child, child);

        assertTrue(manifest.extendsRefs().isEmpty(), "resolved manifest clears extends");
        assertEquals("extends-ordered", manifest.name());
        assertEquals("0.3.1-beta", manifest.version());

        // base + mid + child: duplicate identical `duplicate_policy` tolerated.
        assertEquals(Set.of("shared_policy", "duplicate_policy", "child_policy"),
                manifest.policies().keySet());
        // `search` identical in base and mid survives once; child adds wire_transfer.
        assertEquals(Set.of("search", "wire_transfer"), manifest.tools().keySet());
        assertEquals(List.of("public"), manifest.tools().get("search").clearance());
        assertEquals(List.of("banking", "payments"),
                manifest.tools().get("wire_transfer").clearance());

        assertEquals(3, manifest.interventionPoints().size());
        var input = manifest.interventionPoints().get("input");
        assertNotNull(input);
        assertEquals("shared_policy", input.policyId());
        // Annotations union across base and child on the same point.
        assertEquals("$policy_target.text", input.annotations().get("base_classifier"));
        assertEquals("$pi.snapshot.actor.id", input.annotations().get("child_classifier"));
        assertEquals(2, input.annotations().size());

        var output = manifest.interventionPoints().get("output");
        assertNotNull(output, "output point contributed by nested/mid.yaml");
        assertEquals("shared_policy", output.policyId());
        assertNotNull(manifest.interventionPoints().get("pre_tool_call"));

        assertEquals(Set.of("base_classifier", "child_classifier"),
                manifest.annotators().keySet());
    }

    @Test
    void resolvedManifestEvaluatesWithoutExtendsDeny(@TempDir Path dir) throws IOException {
        var child = copyFixtures(dir).resolve("ordered/child.yaml");
        var manifest = AcsExtendsResolver.resolve("yaml:" + child, child);

        var engine = new AcsPolicyEngine() {
            @Override public String type() {
                return "test";
            }

            @Override public Verdict evaluate(Evaluation evaluation) {
                return Verdict.allow();
            }
        };
        var policy = new AcsManifestPolicy(manifest, "yaml:" + child, child.getParent(),
                type -> "test".equals(type) ? engine : null);

        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(PolicyContext.preAdmission(new AiRequest("hello"))));
    }

    @Test
    void yamlPolicyParserResolvesFileSourcedChains(@TempDir Path dir) throws IOException {
        var child = copyFixtures(dir).resolve("ordered/child.yaml");

        try (var in = Files.newInputStream(child)) {
            var policies = new YamlPolicyParser().parse("yaml:" + child, in);
            assertEquals(1, policies.size());
            var policy = assertInstanceOf(AcsManifestPolicy.class, policies.get(0));
            assertTrue(policy.manifest().extendsRefs().isEmpty(),
                    "parser routing must hand AcsManifestPolicy the resolved manifest");
            assertEquals(3, policy.manifest().policies().size());
        }
    }

    @Test
    void yamlPolicyParserFailsClosedOnBrokenChain(@TempDir Path dir) throws IOException {
        var child = copyFixtures(dir).resolve("missing/child.yaml");

        try (var in = Files.newInputStream(child)) {
            var e = assertThrows(IOException.class,
                    () -> new YamlPolicyParser().parse("yaml:" + child, in));
            assertTrue(e.getMessage().contains("failed to resolve extends file"),
                    "was: " + e.getMessage());
        }
    }

    // --- conflicts --------------------------------------------------------

    @Test
    void policyConflictErrors(@TempDir Path dir) throws IOException {
        var manifest = copyFixtures(dir).resolve("conflict/policy-conflict.yaml");

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + manifest, manifest));
        assertTrue(e.getMessage().contains(
                        "manifest extends conflict for policies.shared_policy from '"
                                + manifest.toRealPath() + "'"),
                "was: " + e.getMessage());
        assertTrue(e.getMessage().endsWith("duplicate definitions must be identical or additive"));
    }

    @Test
    void interventionPointConflictErrors(@TempDir Path dir) throws IOException {
        var manifest = copyFixtures(dir).resolve("conflict/intervention-point-conflict.yaml");

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + manifest, manifest));
        assertTrue(e.getMessage().contains(
                        "manifest extends conflict for intervention_points.input.policy_target"),
                "was: " + e.getMessage());
    }

    @Test
    void policyBindingMergesAtomicallyNotPerField(@TempDir Path dir) throws IOException {
        write(dir, "base.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: atomic-binding
                policies:
                  p:
                    type: test
                intervention_points:
                  input:
                    policy_target: "$snap.input"
                    policy:
                      id: p
                """);
        var child = write(dir, "child.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - base.yaml
                metadata:
                  name: atomic-binding
                intervention_points:
                  input:
                    policy:
                      id: p
                      query: data.acs.verdict
                """);

        // Upstream merge_point_config rejects this: the child's binding is
        // not identical to the base's `{id: p}`, and the binding never
        // merges per field — a per-field merge would wrongly graft `query`
        // onto the base's binding and succeed.
        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + child, child));
        assertTrue(e.getMessage().contains(
                        "manifest extends conflict for intervention_points.input.policy from '"
                                + child.toRealPath() + "'"),
                "was: " + e.getMessage());
    }

    @Test
    void identicalPolicyBindingsAreTolerated(@TempDir Path dir) throws IOException {
        write(dir, "base.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: identical-binding
                policies:
                  p:
                    type: test
                intervention_points:
                  input:
                    policy_target: "$snap.input"
                    policy:
                      id: p
                      query: data.acs.verdict
                """);
        var child = write(dir, "child.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - base.yaml
                metadata:
                  name: identical-binding
                intervention_points:
                  input:
                    policy_target_kind: user_input
                    policy:
                      id: p
                      query: data.acs.verdict
                """);

        // The point configs differ (the child adds policy_target_kind), so
        // the per-field loop runs — and the identical bindings pass the
        // atomic compare.
        var manifest = AcsExtendsResolver.resolve("yaml:" + child, child);
        var input = manifest.interventionPoints().get("input");
        assertNotNull(input);
        assertEquals("p", input.policyId());
        assertEquals("data.acs.verdict", input.query());
        assertEquals("user_input", input.policyTargetKind());
    }

    @Test
    void metadataConflictErrorsWithDottedPath(@TempDir Path dir) throws IOException {
        write(dir, "base.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: base-name
                policies:
                  p:
                    type: test
                """);
        var child = write(dir, "child.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - base.yaml
                metadata:
                  name: child-name
                """);

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + child, child));
        assertTrue(e.getMessage().contains("manifest extends conflict for metadata.name"),
                "was: " + e.getMessage());
    }

    @Test
    void metadataDeepMergesAdditively(@TempDir Path dir) throws IOException {
        write(dir, "base.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: shared
                  description: from-base
                  labels:
                    team: payments
                policies:
                  p:
                    type: test
                intervention_points:
                  input:
                    policy_target: "$snap.input"
                    policy:
                      id: p
                """);
        var child = write(dir, "child.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - base.yaml
                metadata:
                  name: shared
                  labels:
                    tier: gold
                """);

        var manifest = AcsExtendsResolver.resolve("yaml:" + child, child);
        assertEquals("shared", manifest.name());
        assertEquals("from-base", manifest.description(),
                "base-only metadata keys survive the additive deep-merge");
    }

    @Test
    void versionMismatchErrors(@TempDir Path dir) throws IOException {
        write(dir, "base.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: v-base
                policies:
                  p:
                    type: test
                """);
        var child = write(dir, "child.yaml", """
                agent_control_specification_version: "0.4.0-beta"
                extends:
                  - base.yaml
                metadata:
                  name: v-base
                """);

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + child, child));
        assertTrue(e.getMessage().contains(
                        "manifest extends conflict for agent_control_specification_version"),
                "was: " + e.getMessage());
    }

    // --- cycle / missing / depth ------------------------------------------

    @Test
    void cycleErrors(@TempDir Path dir) throws IOException {
        var a = copyFixtures(dir).resolve("cycle/a.yaml");
        var b = dir.resolve("cycle/b.yaml");

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + a, a));
        assertEquals("manifest extends cycle detected: "
                        + a.toRealPath() + " -> " + b.toRealPath() + " -> " + a.toRealPath(),
                e.getMessage());
    }

    @Test
    void missingParentErrors(@TempDir Path dir) throws IOException {
        var child = copyFixtures(dir).resolve("missing/child.yaml");

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + child, child));
        assertTrue(e.getMessage().startsWith("failed to resolve extends file '"),
                "was: " + e.getMessage());
        assertTrue(e.getMessage().contains("does-not-exist.yaml' from '" + child.toRealPath()),
                "was: " + e.getMessage());
    }

    @Test
    void depthLimitErrors(@TempDir Path dir) throws IOException {
        // chain-0 -> chain-1 -> ... -> chain-16: 17 files, one past the cap.
        for (var i = 0; i <= 16; i++) {
            var next = i < 16 ? "extends:\n  - chain-" + (i + 1) + ".yaml\n" : "";
            write(dir, "chain-" + i + ".yaml", """
                    agent_control_specification_version: "0.3.1-beta"
                    """ + next + """
                    metadata:
                      name: deep-chain
                    policies:
                      p:
                        type: test
                    """);
        }
        var top = dir.resolve("chain-0.yaml");

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + top, top));
        assertEquals("manifest extends depth exceeds limit 16 at '"
                + dir.resolve("chain-16.yaml").toRealPath() + "'", e.getMessage());
    }

    // --- confinement ------------------------------------------------------

    @Test
    void escapeOutsideManifestRootIsRejected(@TempDir Path dir) throws IOException {
        var escape = copyFixtures(dir).resolve("confinement/root/escape.yaml");
        var outside = dir.resolve("confinement/outside.yaml");

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + escape, escape));
        assertEquals("extends entry '../outside.yaml' in '" + escape.toRealPath()
                        + "' resolves outside manifest root '" + escape.toRealPath().getParent()
                        + "': '" + outside.toRealPath() + "'",
                e.getMessage());
    }

    @Test
    void insideSubdirIsAllowed(@TempDir Path dir) throws IOException {
        var inside = copyFixtures(dir).resolve("confinement/root/inside.yaml");

        var manifest = AcsExtendsResolver.resolve("yaml:" + inside, inside);
        assertEquals("inside-child", manifest.name());
        assertTrue(manifest.extendsRefs().isEmpty());
        assertNotNull(manifest.policies().get("confinement_policy"));
        assertNotNull(manifest.interventionPoints().get("input"));
    }

    @Test
    void absolutePathEscapeIsRejected(@TempDir Path dir) throws IOException {
        var outsideAbs = write(dir, "outside-abs.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: outside-abs
                policies:
                  p:
                    type: test
                """);
        Files.createDirectories(dir.resolve("root"));
        var child = write(dir, "root/child.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - %s
                metadata:
                  name: abs-escape
                """.formatted(outsideAbs.toAbsolutePath()));

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + child, child));
        assertTrue(e.getMessage().contains("resolves outside manifest root '"
                        + child.toRealPath().getParent() + "'"),
                "was: " + e.getMessage());
    }

    // --- URL extends ------------------------------------------------------

    @Test
    void httpUrlSchemeIsRejected(@TempDir Path dir) throws IOException {
        var url = copyFixtures(dir).resolve("confinement/root/url.yaml");

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + url, url));
        assertEquals("extends URL 'http://example.invalid/base.yaml' uses unsupported "
                + "URL scheme 'http'; only https is allowed", e.getMessage());
    }

    @Test
    void httpsUrlIsNotFetched(@TempDir Path dir) throws IOException {
        var child = write(dir, "remote.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - https://example.invalid/base.yaml
                metadata:
                  name: remote
                """);

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + child, child));
        assertTrue(e.getMessage().contains(
                        "URL extends are not supported by this runtime; vendor the manifest locally"),
                "was: " + e.getMessage());
    }

    @Test
    void httpsUrlObjectFormIsNotFetched(@TempDir Path dir) throws IOException {
        var child = write(dir, "remote-pinned.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - url: https://example.invalid/base.yaml
                    sha256: 0000000000000000000000000000000000000000000000000000000000000000
                metadata:
                  name: remote-pinned
                """);

        var e = assertThrows(IOException.class,
                () -> AcsExtendsResolver.resolve("yaml:" + child, child));
        assertTrue(e.getMessage().contains(
                        "URL extends are not supported by this runtime; vendor the manifest locally"),
                "was: " + e.getMessage());
    }

    // --- bundle rebasing --------------------------------------------------

    @Test
    void baseManifestBundleIsRebasedToTrustRootRelative(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("subdir/policy"));
        Files.writeString(dir.resolve("subdir/policy/check.rego"), "package p\n");
        write(dir, "subdir/base.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                policies:
                  guard_policy:
                    type: fake
                    bundle: ./policy/check.rego
                intervention_points:
                  input:
                    policy_target: "$snap.input"
                    policy_target_kind: user_input
                    policy:
                      id: guard_policy
                """);
        var top = write(dir, "top.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - subdir/base.yaml
                metadata:
                  name: rebased-bundle
                """);

        var manifest = AcsExtendsResolver.resolve("yaml:" + top, top);
        // The base file's `./policy/check.rego` resolves against the BASE
        // file's directory, so the merged manifest must carry the
        // trust-root-relative path, not the child-relative one.
        assertEquals("subdir/policy/check.rego",
                manifest.policies().get("guard_policy").bundle());

        // End-to-end at the AcsManifestPolicy level: a fake engine captures
        // the evaluation, and the captured bundle ref must resolve under the
        // top-level manifest directory exactly as AcsRegoEngine.resolveBundle
        // does (manifestDir-relative resolve + normalize + confinement).
        var captured = new AtomicReference<AcsPolicyEngine.Evaluation>();
        var engine = new AcsPolicyEngine() {
            @Override public String type() {
                return "fake";
            }

            @Override public Verdict evaluate(Evaluation evaluation) {
                captured.set(evaluation);
                return Verdict.allow();
            }
        };
        var policy = new AcsManifestPolicy(manifest, "yaml:" + top, top.getParent(),
                type -> "fake".equals(type) ? engine : null);
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(PolicyContext.preAdmission(new AiRequest("hello"))));

        var evaluation = captured.get();
        assertNotNull(evaluation, "fake engine must have been invoked");
        assertEquals("subdir/policy/check.rego", evaluation.policy().bundle());
        var base = evaluation.manifestDir().toAbsolutePath().normalize();
        var resolved = base.resolve(evaluation.policy().bundle()).normalize();
        assertTrue(resolved.startsWith(base), "rebased ref stays confined to the manifest dir");
        assertTrue(Files.isRegularFile(resolved), "rebased ref lands on the real bundle file");
    }

    // The rebase confinement is canonical where the bundle exists: a symlink
    // under the trust root whose target lies outside it passes the
    // normalize-only startsWith check but must not be rewritten to a
    // trust-root-relative ref — it is left absolute, which
    // AcsRegoEngine.resolveBundle then denies fail-closed.
    @Test
    void symlinkedBundleEscapingTrustRootIsLeftAbsolute(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("outside"));
        Files.writeString(dir.resolve("outside/real.rego"), "package p\n");
        Files.createDirectories(dir.resolve("root"));
        try {
            Files.createSymbolicLink(dir.resolve("root/check.rego"),
                    dir.resolve("outside/real.rego"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "filesystem does not support symlinks: " + e);
        }
        write(dir, "root/base.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                policies:
                  guard_policy:
                    type: rego
                    bundle: ./check.rego
                intervention_points:
                  input:
                    policy_target: "$snap.input"
                    policy:
                      id: guard_policy
                """);
        var top = write(dir, "root/top.yaml", """
                agent_control_specification_version: "0.3.1-beta"
                extends:
                  - base.yaml
                metadata:
                  name: symlink-escape
                """);

        var manifest = AcsExtendsResolver.resolve("yaml:" + top, top);
        var bundle = manifest.policies().get("guard_policy").bundle();
        assertTrue(Path.of(bundle).isAbsolute(),
                "escaping symlinked ref must be left absolute, was: " + bundle);
        assertTrue(bundle.endsWith("check.rego"), "was: " + bundle);
    }

    // --- helpers ----------------------------------------------------------

    private static Path copyFixtures(Path dir) throws IOException {
        for (var name : FIXTURES) {
            var target = dir.resolve(name);
            Files.createDirectories(target.getParent());
            try (var in = AcsExtendsResolverTest.class.getClassLoader()
                    .getResourceAsStream("ms-acs-extends/" + name)) {
                assertNotNull(in, "missing test resource ms-acs-extends/" + name);
                Files.copy(in, target);
            }
        }
        return dir;
    }

    private static Path write(Path dir, String name, String yaml) throws IOException {
        var target = dir.resolve(name);
        Files.writeString(target, yaml);
        return target;
    }
}
