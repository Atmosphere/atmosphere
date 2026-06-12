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

import org.atmosphere.admin.ai.VerifierExampleSource;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.PlanAndVerify;
import org.atmosphere.verifier.annotation.CapabilityScanner;
import org.atmosphere.verifier.annotation.SinkScanner;
import org.atmosphere.verifier.execute.ApprovalGate;
import org.atmosphere.verifier.policy.AutomatonState;
import org.atmosphere.verifier.policy.AutomatonTransition;
import org.atmosphere.verifier.policy.NumericInvariant;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.SecurityAutomaton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Wires Atmosphere's plan-and-verify chain (Meijer's "Guardians of the Agents",
 * CACM Jan 2026) into the multi-agent startup team. One {@link Policy} drives
 * three integration points:
 *
 * <ol>
 *   <li><b>Live plan verification</b> — {@code CeoCoordinator} verifies the
 *       team's plan with {@link PlanAndVerify} <em>before</em> dispatching any
 *       specialist agent.</li>
 *   <li><b>Fail-closed action gate</b> — the CEO's consequential
 *       {@code commit_budget} runs through a {@code GatedToolDispatcher} +
 *       {@link ApprovalGate} at execution time.</li>
 *   <li><b>Console Validation tab</b> — {@link VerifierExampleSource} surfaces
 *       one-click example goals (one passes, three are refused) driven through
 *       the same chain.</li>
 * </ol>
 *
 * <p>The single policy enforces three properties on the team's plan:</p>
 * <ul>
 *   <li><b>Taint</b> — {@code financial_model} output must not reach
 *       {@code publish_to_board.body} (confidential financials can't leave for
 *       the board portal). Sourced from the {@code @Sink} on
 *       {@link StartupTools} via {@link SinkScanner}.</li>
 *   <li><b>SMT</b> — {@code commit_budget.amount <= ref(runway)}, proven for
 *       every runtime value by the SMT layer.</li>
 *   <li><b>Automaton</b> — {@code web_search} (research) must precede
 *       {@code financial_model} / {@code analyze_strategy}.</li>
 * </ul>
 */
@Configuration
public class StartupVerifierConfig {

    /** Tools registered for verification — the team's consequential actions. */
    @Bean
    public ToolRegistry teamToolRegistry() {
        var registry = new DefaultToolRegistry();
        registry.register(new StartupTools());
        return registry;
    }

    /** The startup-team security policy: allowlist + taint + automaton + capability + SMT. */
    @Bean
    public Policy startupTeamPolicy() {
        var allowed = Set.of("web_search", "financial_model", "analyze_strategy",
                "write_report", "check_runway", "request_budget", "commit_budget",
                "publish_to_board");
        return new Policy(
                "startup-team",
                allowed,
                SinkScanner.scan(StartupTools.class),       // taint: @Sink -> TaintRule
                List.of(researchBeforeFinanceAutomaton()),  // ordering
                Set.of("treasury"),                          // granted capabilities
                CapabilityScanner.scan(StartupTools.class))  // @RequiresCapability -> requirements
                .withNumericInvariants(List.of(
                        new NumericInvariant("commit_budget", "amount",
                                NumericInvariant.Op.LE,
                                new NumericInvariant.RefBound("runway"))));
    }

    /**
     * "Research before finance": {@code financial_model} or {@code analyze_strategy}
     * called from the initial state (before any {@code web_search}) drives the
     * automaton into an error state. Once {@code web_search} has run, both are
     * unconstrained.
     */
    private static SecurityAutomaton researchBeforeFinanceAutomaton() {
        return new SecurityAutomaton(
                "research-before-finance",
                List.of(new AutomatonState("research_pending", false),
                        new AutomatonState("researched", false),
                        new AutomatonState("error", true)),
                List.of(new AutomatonTransition("research_pending", "researched", "web_search", null),
                        new AutomatonTransition("research_pending", "error", "financial_model", null),
                        new AutomatonTransition("research_pending", "error", "analyze_strategy", null)),
                "research_pending");
    }

    /** Deterministic plan source so the sample runs without an API key. */
    @Bean
    public AgentRuntime startupPlanRuntime() {
        return new StartupPlanRuntime();
    }

    /**
     * The plan-and-verify chain (allowlist + well-formed + capability + taint +
     * automaton + SMT, discovered via {@code ServiceLoader}). Drives both the
     * live coordinator check and the Console Validation tab.
     */
    @Bean
    public PlanAndVerify planAndVerify(AgentRuntime startupPlanRuntime,
                                       ToolRegistry teamToolRegistry,
                                       Policy startupTeamPolicy) {
        return PlanAndVerify.withDefaults(startupPlanRuntime, teamToolRegistry, startupTeamPolicy);
    }

    /**
     * Fail-closed human-in-the-loop gate for the CEO's consequential action.
     * Defaults to auto-approve so the demo flows; set
     * {@code startup.approvals.auto-approve=false} to see the action denied
     * even on a verified plan.
     */
    @Bean
    public ApprovalGate budgetApprovalGate(
            @Value("${startup.approvals.auto-approve:true}") boolean autoApprove) {
        return new ConfigurableApprovalGate(autoApprove);
    }

    /**
     * Example goals surfaced as one-click buttons in the Console's Validation
     * tab. One passes the chain; three are refused — one per refusal class.
     */
    @Bean
    public VerifierExampleSource startupVerifierExamples() {
        return () -> List.of(
                new VerifierExampleSource.Example(
                        "benign", "Benign — market analysis",
                        "Analyze the market for AI fitness apps and brief the board",
                        "Passes — research, model, report, publish the briefing, commit within runway."),
                new VerifierExampleSource.Example(
                        "taint", "Leak financials (taint)",
                        "Publish our confidential financial model to the board portal",
                        "Refused — taint: financial_model output reaches publish_to_board.body."),
                new VerifierExampleSource.Example(
                        "smt", "Over budget (SMT refuses)",
                        "Commit the full requested budget regardless of runway",
                        "Refused — SMT cannot prove commit_budget.amount <= ref(runway)."),
                new VerifierExampleSource.Example(
                        "automaton", "Skip research (automaton)",
                        "Skip research and jump straight to the financial model",
                        "Refused — automaton: financial_model before web_search (research)."));
    }
}
