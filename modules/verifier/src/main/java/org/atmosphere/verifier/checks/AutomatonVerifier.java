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
package org.atmosphere.verifier.checks;

import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.ast.ConditionalNode;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.AutomatonState;
import org.atmosphere.verifier.policy.AutomatonTransition;
import org.atmosphere.verifier.policy.Condition;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.SecurityAutomaton;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Symbolic-execution check over the workflow's tool-call sequence
 * against each {@link SecurityAutomaton} declared in the
 * {@link Policy}. Captures sequencing properties that pure dataflow
 * verifiers (taint) cannot express — for example, "you must call
 * {@code authenticate} before any {@code post_*} tool" or "calling
 * {@code finalize} terminates the conversation; nothing else may
 * follow."
 *
 * <h3>Algorithm</h3>
 * <p>The plan is executed symbolically over a <em>set</em> of possible
 * automaton states (initially just {@link SecurityAutomaton#initialState()}).
 * On each tool call, every current state advances through its matching
 * transitions:</p>
 * <ul>
 *   <li>An unconditional transition (or one whose
 *       {@link AutomatonTransition#condition() guard} is statically
 *       <em>true</em>) moves to its target state.</li>
 *   <li>A guard that is statically <em>false</em> blocks its transition.</li>
 *   <li>A guard whose truth is not statically known (e.g. it compares a
 *       symbolic reference resolved only at run time) is treated as
 *       possibly-true <em>and</em> possibly-false: both the target state
 *       and the staying state are kept. This is the sound over-approximation
 *       — the verifier never misses an error state a runtime value could
 *       reach.</li>
 *   <li>A call with no matching transition leaves each state unchanged.</li>
 * </ul>
 * <p>If any reachable state is an {@link AutomatonState#isError() error
 * state}, a violation is emitted — once per automaton, so re-entry on a
 * later step doesn't re-fire.</p>
 *
 * <h3>Conditional branches</h3>
 * <p>Each arm of a {@link ConditionalNode} is explored from the state set
 * at the branch point; the resulting state sets are unioned. An error
 * reachable through either arm is a violation.</p>
 *
 * <p>Priority 40 — last in the built-in chain. Cheaper checks
 * (structure 5, allowlist 10, well-formed 20, capability 25, taint 30)
 * run first; the automaton walk is paid only for plans that survived
 * everything else.</p>
 */
public final class AutomatonVerifier implements PlanVerifier {

    static final String CATEGORY = "automaton";

    @Override
    public String name() {
        return "automaton";
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry) {
        if (policy.automata().isEmpty()) {
            return VerificationResult.ok();
        }
        List<Violation> violations = new ArrayList<>();
        for (SecurityAutomaton automaton : policy.automata()) {
            boolean[] errorEmitted = {false};
            walk(automaton, workflow.steps(), Set.of(automaton.initialState()),
                    errorEmitted, violations, "steps");
        }
        return VerificationResult.of(violations);
    }

    /**
     * Symbolically execute a step list from {@code entry}, returning the
     * set of states reachable afterward.
     */
    private Set<String> walk(SecurityAutomaton automaton,
                             List<WorkflowStep> steps,
                             Set<String> entry,
                             boolean[] errorEmitted,
                             List<Violation> out,
                             String prefix) {
        Set<String> current = new HashSet<>(entry);
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            String stepPath = prefix + "[" + i + "]";
            var node = step.node();
            if (node instanceof ToolCallNode call) {
                current = advance(automaton, current, call);
                detectError(automaton, current, stepPath,
                        step.label(), call.toolName(), errorEmitted, out);
            } else if (node instanceof ConditionalNode cond) {
                Set<String> thenStates = walk(automaton, cond.thenSteps(), current,
                        errorEmitted, out, stepPath + ".then");
                Set<String> elseStates = walk(automaton, cond.elseSteps(), current,
                        errorEmitted, out, stepPath + ".otherwise");
                current = new HashSet<>(thenStates);
                current.addAll(elseStates);
            }
        }
        return current;
    }

    /** Compute the successor state set for a single tool call. */
    private static Set<String> advance(SecurityAutomaton automaton,
                                       Set<String> current,
                                       ToolCallNode call) {
        Set<String> next = new HashSet<>();
        for (String state : current) {
            List<AutomatonTransition> matching = matchingTransitions(automaton, state, call.toolName());
            if (matching.isEmpty()) {
                next.add(state);
                continue;
            }
            boolean stayPossible = false;
            for (AutomatonTransition t : matching) {
                Condition.Tristate guard = evalGuard(t.condition(), call);
                if (guard != Condition.Tristate.FALSE) {
                    next.add(t.toState());
                }
                if (guard != Condition.Tristate.TRUE) {
                    stayPossible = true;
                }
            }
            if (stayPossible) {
                next.add(state);
            }
        }
        return next;
    }

    private static Condition.Tristate evalGuard(String condition, ToolCallNode call) {
        if (condition == null) {
            return Condition.Tristate.TRUE;
        }
        try {
            return Condition.parse(condition).evaluateStatic(call.arguments());
        } catch (IllegalArgumentException malformed) {
            // A malformed guard can't be proven false; treat it as unknown
            // so the verifier soundly explores the transition rather than
            // silently dropping it.
            return Condition.Tristate.UNKNOWN;
        }
    }

    private void detectError(SecurityAutomaton automaton,
                             Set<String> current,
                             String stepPath,
                             String stepLabel,
                             String toolName,
                             boolean[] errorEmitted,
                             List<Violation> out) {
        if (errorEmitted[0]) {
            return;
        }
        // Iterate declared-state order for a deterministic message when
        // more than one error state is reachable at once.
        for (AutomatonState state : automaton.states()) {
            if (state.isError() && current.contains(state.name())) {
                out.add(new Violation(
                        CATEGORY,
                        "Automaton '" + automaton.name() + "' entered error state '"
                                + state.name() + "' after step " + stepPath + " ('"
                                + stepLabel + "' calling '" + toolName + "')",
                        stepPath + ".toolName"));
                errorEmitted[0] = true;
                return;
            }
        }
    }

    private static List<AutomatonTransition> matchingTransitions(SecurityAutomaton automaton,
                                                                 String fromState,
                                                                 String toolName) {
        var matches = new ArrayList<AutomatonTransition>();
        for (AutomatonTransition t : automaton.transitions()) {
            if (t.fromState().equals(fromState) && t.toolName().equals(toolName)) {
                matches.add(t);
            }
        }
        return matches;
    }
}
