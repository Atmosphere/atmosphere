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

import org.atmosphere.verifier.ast.ConditionalNode;
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.checks.AutomatonVerifier;
import org.atmosphere.verifier.checks.TaintVerifier;
import org.atmosphere.verifier.checks.WellFormednessVerifier;
import org.atmosphere.verifier.policy.AutomatonState;
import org.atmosphere.verifier.policy.AutomatonTransition;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.SecurityAutomaton;
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

/**
 * Verifiers descend into both arms of a {@link ConditionalNode}: a leak,
 * sequencing breach, or dangling reference reachable through <em>either</em>
 * arm is caught, because the predicate that selects an arm is never trusted
 * to keep an unsafe branch from running.
 */
class ConditionalVerificationTest {

    // ── well-formedness ────────────────────────────────────────────────

    @Test
    void predicateVariableMustBeBound() {
        // 'score' is referenced by the predicate but never produced.
        Workflow wf = new Workflow("branch on nothing", List.of(
                new WorkflowStep("decide", new ConditionalNode(
                        "score >= 80",
                        List.of(new WorkflowStep("f", new ToolCallNode(
                                PlanFixtures.FETCH, Map.of(), "x"))),
                        List.of()))));

        VerificationResult result = new WellFormednessVerifier().verify(
                wf, PlanFixtures.policyAllowing(PlanFixtures.FETCH),
                PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        Violation v = result.violations().get(0);
        assertEquals("well-formed", v.category());
        assertEquals("steps[0].predicate", v.astPath());
        assertTrue(v.message().contains("score"), () -> v.message());
    }

    @Test
    void bindingProducedInOneArmIsNotInScopeAfterTheConditional() {
        // 'onlyThen' is bound only on the then-arm; referencing it after the
        // conditional is a forward/dangling reference because the else-arm
        // could have run instead.
        Workflow wf = new Workflow("leaky scope", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of(), "emails")),
                new WorkflowStep("decide", new ConditionalNode(
                        "emails == ignored",
                        List.of(new WorkflowStep("sum", new ToolCallNode(
                                PlanFixtures.SUMMARIZE,
                                Map.of("input", new SymRef("emails")), "onlyThen"))),
                        List.of())),
                new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND,
                        Map.of("body", new SymRef("onlyThen")), null))));

        VerificationResult result = new WellFormednessVerifier().verify(
                wf, PlanFixtures.policyAllowing(
                        PlanFixtures.FETCH, PlanFixtures.SUMMARIZE, PlanFixtures.SEND),
                PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals("steps[2].arguments.body", result.violations().get(0).astPath());
    }

    @Test
    void bindingProducedOnBothArmsIsInScopeAfter() {
        // 'data' is produced on both arms, so it IS guaranteed afterward.
        Workflow wf = new Workflow("both arms bind", List.of(
                new WorkflowStep("seed", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of(), "flag")),
                new WorkflowStep("decide", new ConditionalNode(
                        "flag == x",
                        List.of(new WorkflowStep("a", new ToolCallNode(
                                PlanFixtures.SUMMARIZE, Map.of(), "data"))),
                        List.of(new WorkflowStep("b", new ToolCallNode(
                                PlanFixtures.FETCH, Map.of(), "data"))))),
                new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND, Map.of("body", new SymRef("data")), null))));

        VerificationResult result = new WellFormednessVerifier().verify(
                wf, PlanFixtures.policyAllowing(
                        PlanFixtures.FETCH, PlanFixtures.SUMMARIZE, PlanFixtures.SEND),
                PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk(), () -> "violations: " + result.violations());
    }

    // ── taint ──────────────────────────────────────────────────────────

    private static final TaintRule INBOX_TO_BODY = new TaintRule(
            "no-inbox-leak", PlanFixtures.FETCH, PlanFixtures.SEND, "body");

    private static Policy taintPolicy() {
        return new Policy("p",
                Set.of(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE, PlanFixtures.SEND),
                List.of(INBOX_TO_BODY), List.of());
    }

    @Test
    void leakInsideABranchIsCaught() {
        Workflow wf = new Workflow("leak in then-arm", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of(), "emails")),
                new WorkflowStep("decide", new ConditionalNode(
                        "emails == ignored",
                        List.of(new WorkflowStep("send", new ToolCallNode(
                                PlanFixtures.SEND,
                                Map.of("body", new SymRef("emails")), null))),
                        List.of()))));

        VerificationResult result = new TaintVerifier().verify(
                wf, taintPolicy(), PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals("steps[1].then[0].arguments.body",
                result.violations().get(0).astPath());
    }

    @Test
    void taintFromEitherArmPersistsPastTheConditional() {
        // 'data' is tainted only on the then-arm; the merge keeps that taint,
        // so the post-conditional send is a leak.
        Workflow wf = new Workflow("taint merge", List.of(
                new WorkflowStep("decide", new ConditionalNode(
                        "x == y",
                        List.of(new WorkflowStep("t", new ToolCallNode(
                                PlanFixtures.FETCH, Map.of(), "data"))),
                        List.of(new WorkflowStep("e", new ToolCallNode(
                                PlanFixtures.SUMMARIZE, Map.of(), "data"))))),
                new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND, Map.of("body", new SymRef("data")), null))));

        VerificationResult result = new TaintVerifier().verify(
                wf, taintPolicy(), PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk(), () -> "branch taint not merged");
        assertEquals("steps[1].arguments.body", result.violations().get(0).astPath());
    }

    @Test
    void cleanConditionalPlanPasses() {
        Workflow wf = new Workflow("no leak", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of(), "emails")),
                new WorkflowStep("decide", new ConditionalNode(
                        "emails == ignored",
                        List.of(new WorkflowStep("send", new ToolCallNode(
                                PlanFixtures.SEND,
                                Map.of("body", "static text"), null))),
                        List.of()))));

        assertTrue(new TaintVerifier().verify(
                wf, taintPolicy(), PlanFixtures.registryWithFixtureTools()).isOk());
    }

    // ── automaton ──────────────────────────────────────────────────────

    private static SecurityAutomaton authBeforeFetch() {
        return new SecurityAutomaton("auth-before-fetch",
                List.of(new AutomatonState("unauth", false),
                        new AutomatonState("auth", false),
                        new AutomatonState("error", true)),
                List.of(new AutomatonTransition("unauth", "auth", "authenticate", null),
                        new AutomatonTransition("unauth", "error", PlanFixtures.FETCH, null),
                        new AutomatonTransition("auth", "auth", PlanFixtures.FETCH, null)),
                "unauth");
    }

    @Test
    void sequencingBreachReachableViaABranchIsCaught() {
        // fetch-before-auth happens only on the then-arm; still caught.
        Workflow wf = new Workflow("branch into error", List.of(
                new WorkflowStep("decide", new ConditionalNode(
                        "x == y",
                        List.of(new WorkflowStep("oops", new ToolCallNode(
                                PlanFixtures.FETCH, Map.of(), "e"))),
                        List.of()))));

        Policy policy = new Policy("p", Set.of("authenticate", PlanFixtures.FETCH),
                List.of(), List.of(authBeforeFetch()));

        VerificationResult result = new AutomatonVerifier().verify(
                wf, policy, PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals("automaton", result.violations().get(0).category());
        assertEquals("steps[0].then[0].toolName", result.violations().get(0).astPath());
    }
}
