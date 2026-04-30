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

import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.checks.AutomatonVerifier;
import org.atmosphere.verifier.policy.AutomatonState;
import org.atmosphere.verifier.policy.AutomatonTransition;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.SecurityAutomaton;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatonVerifierTest {

    private final AutomatonVerifier verifier = new AutomatonVerifier();

    /**
     * "Must authenticate before fetching" — the canonical sequencing
     * policy. States: unauth (initial), auth, error. Transitions:
     * authenticate moves to auth; fetch_emails from unauth moves to
     * error; fetch_emails from auth stays at auth.
     */
    private static SecurityAutomaton authBeforeFetch() {
        return new SecurityAutomaton(
                "auth-before-fetch",
                List.of(
                        new AutomatonState("unauth", false),
                        new AutomatonState("auth", false),
                        new AutomatonState("error", true)),
                List.of(
                        new AutomatonTransition("unauth", "auth", "authenticate", null),
                        new AutomatonTransition("unauth", "error", "fetch_emails", null),
                        new AutomatonTransition("auth", "auth", "fetch_emails", null)),
                "unauth");
    }

    private static Policy policyWith(SecurityAutomaton automaton) {
        return new Policy("p",
                Set.of("authenticate", "fetch_emails"),
                List.of(),
                List.of(automaton));
    }

    @Test
    void fetchBeforeAuthIsRejected() {
        Workflow wf = new Workflow("forgot to auth", List.of(
                new WorkflowStep("oops", new ToolCallNode(
                        "fetch_emails", Map.of("folder", "inbox"), "emails"))));

        VerificationResult result = verifier.verify(
                wf, policyWith(authBeforeFetch()),
                PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals(1, result.violations().size());
        Violation v = result.violations().get(0);
        assertEquals("automaton", v.category());
        assertTrue(v.message().contains("error"),
                () -> "msg: " + v.message());
        assertEquals("steps[0].toolName", v.astPath());
    }

    @Test
    void authThenFetchIsAccepted() {
        Workflow wf = new Workflow("orderly", List.of(
                new WorkflowStep("login", new ToolCallNode(
                        "authenticate", Map.of(), "session")),
                new WorkflowStep("fetch", new ToolCallNode(
                        "fetch_emails", Map.of("folder", "inbox"), "emails"))));

        VerificationResult result = verifier.verify(
                wf, policyWith(authBeforeFetch()),
                PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk(),
                () -> "violations: " + result.violations());
    }

    @Test
    void unmatchedToolCallIsNoOpForAutomaton() {
        // The plan calls `summarize`, which doesn't appear in any
        // transition. The automaton stays in `unauth`; no violation.
        Workflow wf = new Workflow("just summarize", List.of(
                new WorkflowStep("summarize", new ToolCallNode(
                        "summarize", Map.of("input", "x"), "out"))));

        VerificationResult result = verifier.verify(
                wf, policyWith(authBeforeFetch()),
                PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk());
    }

    @Test
    void emptyAutomataListIsTriviallyOk() {
        Policy policy = new Policy("p", Set.of("fetch_emails"), List.of(), List.of());
        Workflow wf = new Workflow("anything", List.of(
                new WorkflowStep("x", new ToolCallNode(
                        "fetch_emails", Map.of(), "y"))));

        assertTrue(verifier.verify(wf, policy,
                PlanFixtures.registryWithFixtureTools()).isOk());
    }

    @Test
    void errorStateOnlyEmitsOneViolationPerAutomaton() {
        // Two illegal fetches before auth — the verifier emits exactly
        // one violation per automaton (re-entry doesn't re-fire).
        Workflow wf = new Workflow("two oops", List.of(
                new WorkflowStep("oops1", new ToolCallNode(
                        "fetch_emails", Map.of(), "a")),
                new WorkflowStep("oops2", new ToolCallNode(
                        "fetch_emails", Map.of(), "b"))));

        VerificationResult result = verifier.verify(
                wf, policyWith(authBeforeFetch()),
                PlanFixtures.registryWithFixtureTools());

        assertEquals(1, result.violations().size(),
                () -> "expected one violation; got: " + result.violations());
    }

    @Test
    void multipleAutomataProduceIndependentViolations() {
        SecurityAutomaton second = new SecurityAutomaton(
                "no-fetch-ever",
                List.of(
                        new AutomatonState("ok", false),
                        new AutomatonState("forbidden", true)),
                List.of(new AutomatonTransition("ok", "forbidden", "fetch_emails", null)),
                "ok");

        Policy policy = new Policy("p",
                Set.of("fetch_emails"),
                List.of(),
                List.of(authBeforeFetch(), second));

        Workflow wf = new Workflow("fetch", List.of(
                new WorkflowStep("x", new ToolCallNode(
                        "fetch_emails", Map.of(), "y"))));

        VerificationResult result = verifier.verify(
                wf, policy, PlanFixtures.registryWithFixtureTools());

        assertEquals(2, result.violations().size(),
                () -> "expected one per automaton; got: " + result.violations());
    }
}
