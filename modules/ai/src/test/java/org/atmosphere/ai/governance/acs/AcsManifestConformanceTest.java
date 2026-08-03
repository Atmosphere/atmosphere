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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins byte-for-byte conformance with Microsoft's ACS manifest contract.
 * The fixtures under {@code modules/ai/src/test/resources/ms-acs/} are
 * verbatim copies of upstream's own contract fixtures
 * ({@code policy-engine/core/tests/fixtures/manifests/} in
 * microsoft/agent-governance-toolkit) — the weekly {@code ms-yaml-conformance}
 * workflow diffs our copies against upstream {@code main}, so an upstream
 * shape change lands here as a failing diff, not silent drift.
 *
 * <p>Every parse in this test goes through {@link YamlPolicyParser} — the
 * production routing an operator's policy file takes — never through the
 * ACS parser directly.</p>
 */
class AcsManifestConformanceTest {

    @Test
    void parsesCanonicalContractFixtureVerbatim() throws IOException {
        var policy = parseSingle("ms-acs/canonical-all-interventions.yaml");

        assertEquals("canonical-all-interventions", policy.name());
        assertEquals("0.3.1-beta", policy.version());

        var manifest = policy.manifest();
        assertEquals(List.of("./base-controls.yaml"), manifest.extendsRefs());
        assertEquals(5, manifest.policies().size());
        assertEquals("rego", manifest.policies().get("input_policy").type());
        assertEquals("./policy/input.tar.gz", manifest.policies().get("input_policy").bundle());
        assertEquals("test", manifest.policies().get("lifecycle_policy").type());

        // All eight upstream intervention points survive the round-trip.
        assertEquals(8, manifest.interventionPoints().size());
        var preTool = manifest.interventionPoints().get("pre_tool_call");
        assertNotNull(preTool);
        assertEquals("$snap.tool_call.args", preTool.policyTarget());
        assertEquals("tool_args", preTool.policyTargetKind());
        assertEquals("$snap.tool_call.name", preTool.toolNameFrom());
        assertEquals("tool_authz_policy", preTool.policyId());

        var input = manifest.interventionPoints().get("input");
        assertNotNull(input);
        assertEquals("data.agent_control_specification.input.verdict", input.query());
        assertEquals(2, input.annotations().size());
        assertEquals("$policy_target.text", input.annotations().get("prompt_classifier"));

        var search = manifest.tools().get("search");
        assertNotNull(search);
        assertEquals(List.of("public", "customer_profile"), search.clearance());
        assertEquals(List.of("customer_profile"), search.securityLabels());

        assertEquals(8, manifest.annotators().size());
        assertEquals("endpoint", manifest.annotators().get("actor_context").type());
        assertEquals("classifier", manifest.annotators().get("prompt_classifier").type());
        assertEquals("llm", manifest.annotators().get("output_safety").type());
    }

    @Test
    void parsesMinimalContractFixtureVerbatim() throws IOException {
        var policy = parseSingle("ms-acs/minimal-all-interventions.yaml");

        assertEquals("minimal-all-interventions", policy.name());
        assertEquals("0.3.1-beta", policy.version());
        var manifest = policy.manifest();
        assertTrue(manifest.extendsRefs().isEmpty(),
                "minimal fixture declares an empty extends chain");
        assertEquals(8, manifest.interventionPoints().size());
        assertEquals("test", manifest.policies().get("test_policy").type());
    }

    // The canonical fixture declares an extends chain we do not resolve —
    // evaluation must deny fail-closed with the reserved runtime-error
    // reason, never admit (Correctness Invariant #6 / ACS safety model).
    @Test
    void unresolvedExtendsChainDeniesFailClosed() throws IOException {
        var policy = parseSingle("ms-acs/canonical-all-interventions.yaml");

        var decision = policy.evaluate(
                PolicyContext.preAdmission(new AiRequest("hello")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().startsWith(AcsPolicyEngine.RUNTIME_ERROR),
                "reserved runtime-error reason, was: " + deny.reason());
    }

    // The minimal fixture binds `test`-typed policies. No engine for that
    // type is registered in this module, so a bound point must deny
    // fail-closed — a manifest never silently admits because its engine is
    // missing from the classpath.
    @Test
    void boundPointWithoutEngineDeniesFailClosed() throws IOException {
        var policy = parseSingle("ms-acs/minimal-all-interventions.yaml");

        var decision = policy.evaluate(
                PolicyContext.preAdmission(new AiRequest("hello")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().contains("no AcsPolicyEngine registered"),
                "engine-unavailable reason, was: " + deny.reason());
    }

    // A phase whose intervention point is NOT bound in the manifest admits:
    // ACS is host-driven — a point the host never presents to the runtime
    // is host scope, not fail-open.
    @Test
    void unboundInterventionPointAdmits() throws IOException {
        var yaml = """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: tool-only
                policies:
                  tool_policy:
                    type: test
                intervention_points:
                  pre_tool_call:
                    policy_target: "$snap.tool_call.args"
                    policy_target_kind: tool_args
                    policy:
                      id: tool_policy
                """;
        var policy = parseSingleFromString(yaml);

        // No tool_name metadata → the `input` point, which is unbound here.
        var decision = policy.evaluate(
                PolicyContext.preAdmission(new AiRequest("hello")));
        assertInstanceOf(PolicyDecision.Admit.class, decision);

        // With tool_name metadata the bound pre_tool_call point fires and
        // denies (no engine registered for `test`).
        var toolCall = new AiRequest("call_tool:search", null, null, null, null, null, null,
                Map.of("tool_name", "search", "tool_args", Map.of("query", "q")), List.of());
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(PolicyContext.preAdmission(toolCall)));
    }

    // Verdict mapping through a fake engine: allow → Admit, deny → Deny
    // with the policy's reason, transform on the whole input target →
    // Transform with the rewritten message, invalid transform target →
    // fail-closed Deny.
    @Test
    void mapsEngineVerdictsOntoPolicyDecisions() throws IOException {
        var yaml = """
                agent_control_specification_version: "0.3.1-beta"
                metadata:
                  name: verdict-mapping
                policies:
                  input_policy:
                    type: fake
                intervention_points:
                  input:
                    policy_target: "$snap.input"
                    policy_target_kind: user_input
                    policy:
                      id: input_policy
                """;
        var manifest = parseSingleFromString(yaml).manifest();

        var allow = policyWith(manifest, AcsPolicyEngine.Verdict.allow());
        assertInstanceOf(PolicyDecision.Admit.class,
                allow.evaluate(PolicyContext.preAdmission(new AiRequest("hi"))));

        var deny = policyWith(manifest,
                AcsPolicyEngine.Verdict.deny("external_recipient_blocked", "blocked"));
        var denied = assertInstanceOf(PolicyDecision.Deny.class,
                deny.evaluate(PolicyContext.preAdmission(new AiRequest("hi"))));
        assertEquals("external_recipient_blocked", denied.reason());

        var transform = policyWith(manifest,
                AcsPolicyEngine.Verdict.transform("$policy_target", "[REDACTED]"));
        var transformed = assertInstanceOf(PolicyDecision.Transform.class,
                transform.evaluate(PolicyContext.preAdmission(new AiRequest("TRACK-123"))));
        assertEquals("[REDACTED]", transformed.modifiedRequest().message());

        var badTransform = policyWith(manifest,
                AcsPolicyEngine.Verdict.transform("$policy_target.body", "x"));
        var badDeny = assertInstanceOf(PolicyDecision.Deny.class,
                badTransform.evaluate(PolicyContext.preAdmission(new AiRequest("hi"))));
        assertTrue(badDeny.reason().contains("invalid transform target"));
    }

    private static AcsManifestPolicy policyWith(AcsManifest manifest,
                                                AcsPolicyEngine.Verdict verdict) {
        var engine = new AcsPolicyEngine() {
            @Override public String type() {
                return "fake";
            }

            @Override public Verdict evaluate(Evaluation evaluation) {
                assertEquals("input", evaluation.interventionPoint());
                assertEquals("$snap.input",
                        ((Map<?, ?>) evaluation.input().get("policy_target")).get("path"));
                return verdict;
            }
        };
        return new AcsManifestPolicy(manifest, "test:inline", null,
                type -> "fake".equals(type) ? engine : null);
    }

    private static AcsManifestPolicy parseSingle(String resource) throws IOException {
        try (InputStream in = AcsManifestConformanceTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            var policies = new YamlPolicyParser().parse("yaml:" + resource, in);
            assertEquals(1, policies.size(), "one policy per ACS manifest document");
            return assertInstanceOf(AcsManifestPolicy.class, policies.get(0));
        }
    }

    private static AcsManifestPolicy parseSingleFromString(String yaml) throws IOException {
        try (InputStream in = new java.io.ByteArrayInputStream(
                yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            var policies = new YamlPolicyParser().parse("test:inline", in);
            assertEquals(1, policies.size());
            return assertInstanceOf(AcsManifestPolicy.class, policies.get(0));
        }
    }
}
