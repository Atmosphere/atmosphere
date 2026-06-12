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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.PlanAndVerify;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.execute.ApprovalDeniedException;
import org.atmosphere.verifier.execute.GatedToolDispatcher;
import org.atmosphere.verifier.execute.RegistryToolDispatcher;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the plan-and-verify integration on the multi-agent startup team:
 * one benign team plan clears the chain (and executes), and each of the three
 * refusal classes (taint, SMT, automaton) blocks its plan before any tool runs.
 * The approval gate (Approach 2) is asserted to fail closed.
 *
 * <p>Pure unit test — builds the same beans {@link StartupVerifierConfig}
 * publishes; no Spring context, no LLM, no network.</p>
 */
class StartupTeamVerifierTest {

    private final StartupVerifierConfig config = new StartupVerifierConfig();
    private final ToolRegistry registry = config.teamToolRegistry();
    private final Policy policy = config.startupTeamPolicy();
    private final AgentRuntime runtime = config.startupPlanRuntime();
    private final PlanAndVerify planAndVerify =
            PlanAndVerify.withDefaults(runtime, registry, policy);

    private Set<String> refusalCategories(String goal) {
        Workflow plan = planAndVerify.plan(goal);
        VerificationResult verdict = planAndVerify.verify(plan);
        return verdict.violations().stream().map(Violation::category)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void benignTeamPlanClearsChainAndExecutes() {
        String goal = "Analyze the market for AI fitness apps and brief the board";
        VerificationResult verdict = planAndVerify.verify(planAndVerify.plan(goal));
        assertTrue(verdict.isOk(),
                "benign plan must clear every verifier; got: " + verdict.violations());

        // End-to-end: a clean plan actually runs and binds its results.
        Map<String, Object> env = planAndVerify.run(goal, Map.of());
        assertTrue(env.containsKey("published"),
                "benign plan should execute through publish_to_board");
        assertTrue(env.containsKey("receipt"),
                "benign plan should commit the budget within runway");
    }

    @Test
    void confidentialLeakRefusedByTaint() {
        var categories = refusalCategories(
                "Publish our confidential financial model to the board portal");
        assertTrue(categories.contains("taint"),
                "financial_model -> publish_to_board.body must be a taint violation; got " + categories);
    }

    @Test
    void overBudgetRefusedBySmt() {
        var categories = refusalCategories(
                "Commit the full requested budget regardless of runway");
        assertTrue(categories.contains("smt"),
                "commit_budget.amount <= ref(runway) must be unprovable -> smt violation; got " + categories);
    }

    @Test
    void financeBeforeResearchRefusedByAutomaton() {
        var categories = refusalCategories(
                "Skip research and jump straight to the financial model");
        assertTrue(categories.contains("automaton"),
                "financial_model before web_search must be an automaton violation; got " + categories);
    }

    @Test
    void approvalGateFailsClosedWhenNotApproved() {
        var denied = new GatedToolDispatcher(
                new RegistryToolDispatcher(registry), new ConfigurableApprovalGate(false));
        assertThrows(ApprovalDeniedException.class,
                () -> denied.dispatch("commit_budget", Map.of("amount", "50000")),
                "a non-approving gate must block the consequential action (fail closed)");
    }

    @Test
    void approvalGateAllowsWhenApproved() {
        var approved = new GatedToolDispatcher(
                new RegistryToolDispatcher(registry), new ConfigurableApprovalGate(true));
        String receipt = approved.dispatch("commit_budget", Map.of("amount", "50000"));
        assertTrue(receipt.contains("committed"), "approved commit should fire the tool");
        assertFalse(receipt.isBlank());
        assertEquals("OK — committed $50000", receipt);
    }
}
