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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test that drives the real {@link OpaSubprocessEvaluator} +
 * {@link RegoPolicy} against the actual {@code opa} binary — not a
 * hand-crafted JSON fixture fed to the parser. It writes a Rego module,
 * forks {@code opa eval}, and asserts the decision the binary actually
 * returns: a permitted request admits, a forbidden request denies.
 *
 * <p><b>Gating:</b> the test self-skips (JUnit {@code assumeTrue}) when
 * no {@code opa} binary is reachable, so it is green on runners without
 * the engine. CI installs OPA in a dedicated lane
 * ({@code .github/workflows/policy-as-code-engines.yml}) so the test
 * genuinely executes there. Point it at a specific binary with
 * {@code -Datmosphere.opa.path=/path/to/opa} or the {@code OPA_BINARY}
 * environment variable.</p>
 */
class OpaSubprocessEvaluatorIntegrationTest {

    /**
     * Traditional OPA decision rule: {@code allow} is a boolean that is
     * true only for the billing agent with a non-empty message.
     */
    private static final String GOVERNANCE_REGO = """
            package atmosphere.governance
            default allow = false
            allow {
                input.agent_id == "billing-agent"
                input.message != ""
            }
            """;

    private static final String QUERY = "data.atmosphere.governance.allow";

    private static String opaBinary;

    @BeforeAll
    static void resolveBinary() {
        opaBinary = PolicyEngineBinaries.resolve("atmosphere.opa.path", "OPA_BINARY", "opa",
                "version");
        assumeTrue(opaBinary != null,
                "opa binary not found on PATH (set -Datmosphere.opa.path or OPA_BINARY) — skipping");
    }

    private RegoPolicy policy() {
        return new RegoPolicy(
                "atmosphere.governance",
                "rego:integration.rego",
                "1.0",
                GOVERNANCE_REGO,
                QUERY,
                new OpaSubprocessEvaluator(opaBinary, Duration.ofSeconds(10)));
    }

    @Test
    void permittedRequestAdmits() {
        // billing-agent + non-empty message satisfies the allow rule.
        var decision = policy().evaluate(PolicyContext.preAdmission(new AiRequest(
                "what is my balance", "", "gpt-4o", "user-42", "sess-1",
                "billing-agent", "conv-9", Map.of(), List.of())));
        assertInstanceOf(PolicyDecision.Admit.class, decision,
                "real opa must ADMIT a billing-agent request, got: " + decision);
    }

    @Test
    void forbiddenRequestDenies() {
        // A different agent id fails the allow rule → default allow = false → Deny.
        var decision = policy().evaluate(PolicyContext.preAdmission(new AiRequest(
                "transfer funds", "", "gpt-4o", "user-42", "sess-1",
                "evil-agent", "conv-9", Map.of(), List.of())));
        assertInstanceOf(PolicyDecision.Deny.class, decision,
                "real opa must DENY a non-billing agent request, got: " + decision);
    }

    @Test
    void evaluatorReturnsContrastingDecisionsFromOneBinary() throws IOException {
        // Drive the evaluator directly to prove a real Allow AND a real Deny
        // come out of the same opa process invocation — the contrast the
        // stub-fed parser tests cannot establish.
        var evaluator = new OpaSubprocessEvaluator(opaBinary, Duration.ofSeconds(10));
        var allow = evaluator.evaluate(GOVERNANCE_REGO, QUERY,
                Map.of("agent_id", "billing-agent", "message", "hello"));
        var deny = evaluator.evaluate(GOVERNANCE_REGO, QUERY,
                Map.of("agent_id", "billing-agent", "message", ""));
        assertTrue(allow.allowed(), "non-empty billing message must be allowed by real opa");
        assertTrue(!deny.allowed(), "empty message must be denied by real opa");
    }
}
