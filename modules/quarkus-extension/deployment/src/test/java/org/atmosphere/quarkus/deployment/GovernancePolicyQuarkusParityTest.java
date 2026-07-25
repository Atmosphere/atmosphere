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
package org.atmosphere.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.cost.CostCeilingAccountant;
import org.atmosphere.ai.cost.TokenPricing;
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.atmosphere.ai.governance.DenyListPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.KillSwitchPolicy;
import org.atmosphere.ai.governance.MessageLengthPolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.PolicyRing;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarkus-runtime parity test for the governance policy plane. Verifies
 * that governance primitives loaded on a real Quarkus deployment
 * classloader return the same decisions they do under Spring Boot —
 * identical semantics across both runtimes (Mode Parity invariant).
 *
 * <p>Until this landed, only source parity was proven
 * ({@code PolicyPlaneSourceParityTest} in modules/ai). This runs the
 * same admission path under {@link QuarkusExtensionTest}, catching
 * Quarkus-specific failure modes (Jandex indexing, build-time class
 * rewriting, deployment-classloader isolation) that source tests
 * can't.</p>
 */
public class GovernancePolicyQuarkusParityTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar
                    .addClass(GovernancePolicyQuarkusParityTest.class))
            .overrideConfigKey("quarkus.http.test-port", "0");

    private static PolicyContext preAdm(String msg) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest(msg, null, null, "alice", null, null, null, null, null),
                "");
    }

    @Test
    public void killSwitchEvaluatesUnderQuarkusClassloader() {
        var killSwitch = new KillSwitchPolicy();
        assertInstanceOf(PolicyDecision.Admit.class, killSwitch.evaluate(preAdm("hello")));

        killSwitch.arm("incident-quarkus", "quarkus-oncall");
        var deny = assertInstanceOf(PolicyDecision.Deny.class, killSwitch.evaluate(preAdm("hello")));
        assertTrue(deny.reason().contains("incident-quarkus"),
                "kill-switch reason must propagate identically to Spring Boot behaviour");
    }

    @Test
    public void denyListEvaluatesIdenticallyToSpringBoot() {
        var policy = new DenyListPolicy("quarkus-block", "DROP TABLE");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(preAdm("safe prompt")));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(preAdm("drop table users")));
    }

    @Test
    public void messageLengthPolicyEnforcesCapUnderQuarkus() {
        var policy = new MessageLengthPolicy("cap-50", 50);
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(preAdm("short")));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(preAdm("x".repeat(51))));
    }

    @Test
    public void yamlParserRoundTripsUnderQuarkus() throws Exception {
        // Also proves SnakeYAML loads + parses under Quarkus's classloader.
        // This test
        // proves the YAML parser loads and parses under Quarkus's
        // classloader — covering the "does SnakeYAML surface
        // deployment-classloader isolation issues?" concern.
        var yaml = """
                version: "1.0"
                policies:
                  - name: quarkus-deny-list
                    type: deny-list
                    version: "1"
                    config:
                      phrases: [DROP TABLE]
                  - name: quarkus-msg-cap
                    type: message-length
                    version: "1"
                    config:
                      max-chars: 100
                """;
        List<GovernancePolicy> parsed;
        try (var in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            parsed = new YamlPolicyParser().parse("quarkus-parity", in);
        }
        assertEquals(2, parsed.size());
        assertInstanceOf(DenyListPolicy.class, parsed.get(0));
        assertInstanceOf(MessageLengthPolicy.class, parsed.get(1));

        // And the parsed policies evaluate correctly.
        assertInstanceOf(PolicyDecision.Deny.class,
                parsed.get(0).evaluate(preAdm("please drop table users")));
    }

    @Test
    public void costCeilingObservabilityToEnforcementLoopUnderQuarkus() {
        // Same loop the Spring Boot side pins in
        // AtmosphereCostAccountantAutoConfigurationTest: TokenUsage →
        // CostCeilingAccountant → TokenPricing → addCost → the next
        // request-side inspect Blocks — identical semantics on the Quarkus
        // deployment classloader (Mode Parity invariant).
        var guardrail = new CostCeilingGuardrail(0.004);
        var accountant = new CostCeilingAccountant(guardrail, TokenPricing.flat(1.0, 2.0));
        org.slf4j.MDC.put("business.tenant.id", "acme-parity");
        try {
            assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                    guardrail.inspectRequest(new AiRequest("hello")),
                    "under budget the request must pass");

            // 2000 in @ $1/M + 1500 out @ $2/M = $0.005 ≥ the $0.004 ceiling.
            accountant.record("acme-parity",
                    TokenUsage.of(2_000, 1_500, 3_500, "test-model"), "test-model");
            assertTrue(guardrail.spent("acme-parity") >= 0.004,
                    "the accountant must accrue spend into the guardrail bucket");

            var block = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                    guardrail.inspectRequest(new AiRequest("hello")),
                    "over budget the request must Block — identical to Spring Boot behaviour");
            assertTrue(block.reason().contains("acme-parity"),
                    "the block reason must name the tenant: " + block.reason());
        } finally {
            org.slf4j.MDC.remove("business.tenant.id");
        }
    }

    @Test
    public void policyRingCompositionWorksUnderQuarkus() {
        var ring = PolicyRing.builder("quarkus-ring")
                .ring(1, new KillSwitchPolicy(),
                        new DenyListPolicy("block", "FORBIDDEN"))
                .ring(2, new MessageLengthPolicy("cap", 200))
                .build();

        // Happy path
        assertInstanceOf(PolicyDecision.Admit.class, ring.evaluate(preAdm("normal message")));

        // Ring 1 deny short-circuits ring 2
        assertInstanceOf(PolicyDecision.Deny.class,
                ring.evaluate(preAdm("this is FORBIDDEN speech")));

        // Ring 2 deny when content is fine but size is over cap
        assertInstanceOf(PolicyDecision.Deny.class, ring.evaluate(preAdm("x".repeat(300))));
    }
}
