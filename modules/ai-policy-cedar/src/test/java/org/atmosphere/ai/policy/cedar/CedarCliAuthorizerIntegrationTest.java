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
package org.atmosphere.ai.policy.cedar;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test that drives the real {@link CedarCliAuthorizer} +
 * {@link CedarPolicy} against the actual {@code cedar} binary — not a
 * hand-crafted decision string fed to the parser. It writes a Cedar policy,
 * an entities file, and a request, forks {@code cedar authorize}, and
 * asserts the decision the binary actually returns: a permitted request
 * admits, a forbidden request denies.
 *
 * <p>This is the regression guard for the three bugs that made the adapter
 * deny <i>everything</i> against a real binary: (1) the omitted mandatory
 * {@code --entities} argument, (2) parsing a non-existent JSON envelope
 * instead of the plain-text {@code ALLOW}/{@code DENY} token, and (3) the
 * inverted exit-code contract (real {@code cedar} returns {@code 0} on
 * Allow, {@code 2} on Deny). Before the fix this test's
 * {@link #permittedRequestAdmits()} case denied with a usage error.</p>
 *
 * <p><b>Gating:</b> the test self-skips (JUnit {@code assumeTrue}) when no
 * {@code cedar} binary is reachable, so it is green on runners without the
 * engine. CI installs the Cedar CLI in a dedicated lane
 * ({@code .github/workflows/policy-as-code-engines.yml}) so the test
 * genuinely executes there. Point it at a specific binary with
 * {@code -Datmosphere.cedar.path=/path/to/cedar} or the {@code CEDAR_BINARY}
 * environment variable.</p>
 */
class CedarCliAuthorizerIntegrationTest {

    /**
     * Permits exactly {@code User::"user-42"} invoking {@code Agent::"billing-agent"}.
     * {@link CedarPolicy} maps {@code userId} → {@code User::"<userId>"},
     * a fixed {@code Action::"invoke"}, and {@code agentId} → {@code Agent::"<agentId>"}.
     */
    private static final String BILLING_CEDAR = """
            @id("support-billing-agent")
            permit(
                principal == User::"user-42",
                action == Action::"invoke",
                resource == Agent::"billing-agent"
            );
            """;

    private static String cedarBinary;

    @BeforeAll
    static void resolveBinary() {
        cedarBinary = PolicyEngineBinaries.resolve("atmosphere.cedar.path", "CEDAR_BINARY", "cedar",
                "--version");
        assumeTrue(cedarBinary != null,
                "cedar binary not found on PATH (set -Datmosphere.cedar.path or CEDAR_BINARY) — skipping");
    }

    private CedarPolicy policy() {
        return new CedarPolicy(
                "support-billing-agent",
                "cedar:integration.cedar",
                "1.0",
                BILLING_CEDAR,
                new CedarCliAuthorizer(cedarBinary, Duration.ofSeconds(10)));
    }

    @Test
    void permittedRequestAdmits() {
        // user-42 invoking billing-agent matches the permit -> real cedar ALLOW (exit 0).
        var decision = policy().evaluate(PolicyContext.preAdmission(new AiRequest(
                "what is my balance", "", "gpt-4o", "user-42", "sess-1",
                "billing-agent", "conv-9", Map.of(), List.of())));
        assertInstanceOf(PolicyDecision.Admit.class, decision,
                "real cedar must ADMIT the permitted request, got: " + decision);
    }

    @Test
    void forbiddenRequestDenies() {
        // A different principal matches no permit -> real cedar DENY (exit 2).
        var decision = policy().evaluate(PolicyContext.preAdmission(new AiRequest(
                "what is my balance", "", "gpt-4o", "user-99", "sess-1",
                "billing-agent", "conv-9", Map.of(), List.of())));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision,
                "real cedar must DENY a non-matching principal, got: " + decision);
        assertTrue(deny.reason().contains("cedar policy denied"),
                "deny reason must come from the real DENY decision: " + deny.reason());
    }

    @Test
    void authorizerReturnsContrastingDecisionsFromOneBinary() {
        // Drive the authorizer directly to prove a real Allow AND a real Deny
        // come out of the same cedar binary — the contrast the stub-fed parser
        // tests cannot establish, and the masking bug this whole fix is about.
        var authorizer = new CedarCliAuthorizer(cedarBinary, Duration.ofSeconds(10));
        var allow = authorizer.authorize(BILLING_CEDAR,
                "User::\"user-42\"", "Action::\"invoke\"", "Agent::\"billing-agent\"", Map.of());
        var deny = authorizer.authorize(BILLING_CEDAR,
                "User::\"user-99\"", "Action::\"invoke\"", "Agent::\"billing-agent\"", Map.of());
        assertTrue(allow.allowed(),
                "real cedar must ALLOW user-42 on billing-agent, got: " + allow);
        assertTrue(!deny.allowed(),
                "real cedar must DENY user-99, got: " + deny);
        assertTrue(allow.matchedPolicies().contains("support-billing-agent"),
                "verbose matched-policy block must surface the @id: " + allow.matchedPolicies());
    }
}
