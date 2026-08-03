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
package org.atmosphere.ai.policy.rego;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.atmosphere.ai.governance.acs.AcsManifestPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end ACS evaluation against the REAL {@code opa} binary, mirroring
 * the manifest + Rego policy from Microsoft's ACS tutorial (Tutorial 55):
 * the operator writes {@code manifest.yaml} next to a {@code policy/}
 * bundle, Atmosphere loads it through the production {@link YamlPolicyParser}
 * routing, and the three tutorial outcomes — allow, transform, deny — come
 * back as {@link PolicyDecision}s.
 *
 * <p><b>Gating:</b> self-skips ({@code assumeTrue}) when {@code opa} is
 * absent; the {@code policy-as-code-engines} CI lane installs opa and
 * asserts the suite ran.</p>
 */
class AcsRegoEngineIntegrationTest {

    private static String opaBinary;

    @BeforeAll
    static void requireOpa() {
        opaBinary = PolicyEngineBinaries.resolve("atmosphere.opa.path", "OPA_BINARY", "opa",
                "version");
        assumeTrue(opaBinary != null,
                "opa binary not available — set -Datmosphere.opa.path or OPA_BINARY");
    }

    private static final String MANIFEST = """
            agent_control_specification_version: "0.3.1-beta"
            metadata:
              name: acs-email-tutorial
            policies:
              email_policy:
                type: rego
                bundle: ./policy
                query: data.agent_control_specification.email_policy.verdict
            intervention_points:
              input:
                policy_target: "$snap.input"
                policy_target_kind: user_input
                policy:
                  id: email_policy
            """;

    // The tutorial's three outcomes, keyed off the input text instead of
    // tool args so the policy exercises the `input` intervention point that
    // Atmosphere's PRE_ADMISSION seam presents.
    private static final String POLICY = """
            package agent_control_specification.email_policy

            import rego.v1

            default verdict := {"decision": "allow"}

            verdict := {
              "decision": "deny",
              "reason": "external_recipient_blocked",
              "message": "Messages to external recipients are blocked."
            } if {
              input.intervention_point == "input"
              contains(input.policy_target.value, "@example.net")
            }

            verdict := {
              "decision": "transform",
              "reason": "redact_tracking_token",
              "transform": {
                "path": "$policy_target",
                "value": "Tracking token: [REDACTED]"
              }
            } if {
              input.intervention_point == "input"
              contains(input.policy_target.value, "TRACK-")
              not contains(input.policy_target.value, "@example.net")
            }
            """;

    private AcsManifestPolicy load(Path dir) throws IOException {
        var manifestFile = dir.resolve("manifest.yaml");
        Files.writeString(manifestFile, MANIFEST);
        var policyDir = dir.resolve("policy");
        Files.createDirectories(policyDir);
        Files.writeString(policyDir.resolve("email_policy.rego"), POLICY);

        try (var in = Files.newInputStream(manifestFile)) {
            var policies = new YamlPolicyParser().parse("yaml:" + manifestFile, in);
            assertEquals(1, policies.size());
            return (AcsManifestPolicy) policies.get(0);
        }
    }

    @Test
    void allowsNormalInput(@TempDir Path dir) throws IOException {
        var policy = load(dir);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("Your case is ready.")));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    @Test
    void deniesExternalRecipientWithTutorialReason(@TempDir Path dir) throws IOException {
        var policy = load(dir);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("Send this to partner@example.net please")));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertEquals("external_recipient_blocked", deny.reason());
    }

    @Test
    void transformsTrackingTokenIntoRedactedMessage(@TempDir Path dir) throws IOException {
        var policy = load(dir);
        var decision = policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("Your case is ready. Tracking token: TRACK-123")));
        var transform = assertInstanceOf(PolicyDecision.Transform.class, decision);
        assertEquals("Tracking token: [REDACTED]", transform.modifiedRequest().message());
    }

    // POST_RESPONSE with no bound `output` point is host scope — admitted
    // without consulting opa, proving unbound points are not fail-open
    // engine calls.
    @Test
    void unboundOutputPointAdmits(@TempDir Path dir) throws IOException {
        var policy = load(dir);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("q"), "response text"));
        assertInstanceOf(PolicyDecision.Admit.class, decision);
    }

    // A tool-call intent (tool_name metadata) maps to pre_tool_call, which
    // this manifest does not bind — admitted, while the SAME request text
    // through the bound input point would deny. Proves point selection is
    // metadata-driven, not text-driven.
    @Test
    void toolCallIntentUsesPreToolCallPoint(@TempDir Path dir) throws IOException {
        var policy = load(dir);
        var toolCall = new AiRequest("call_tool:send_email", null, null, null, null, null,
                null, Map.of("tool_name", "send_email",
                        "tool_args", Map.of("to", "partner@example.net")), List.of());
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(PolicyContext.preAdmission(toolCall)));
    }
}
