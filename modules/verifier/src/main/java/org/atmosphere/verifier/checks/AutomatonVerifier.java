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
import org.atmosphere.verifier.ast.PlanNode;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.AutomatonState;
import org.atmosphere.verifier.policy.AutomatonTransition;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.SecurityAutomaton;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>For each automaton, set the current state to the declared
 * {@link SecurityAutomaton#initialState()}. Walk the plan's tool calls
 * in order; on each call, look for a transition with matching
 * {@code fromState} + {@code toolName}. If found, advance the state.
 * If the new state is an {@link AutomatonState#isError() error state},
 * emit a violation. If no matching transition exists, the call is a
 * no-op for this automaton (an unmatched call doesn't violate — it's
 * just outside the automaton's vocabulary).</p>
 *
 * <h3>Phase-5 minimum scope</h3>
 * <ul>
 *   <li>Only equality on {@code fromState} + {@code toolName} is matched.
 *       The {@link AutomatonTransition#condition()} field is ignored;
 *       guarded transitions reduce to unconditional ones until the
 *       condition grammar lands.</li>
 *   <li>The first matching transition wins. Authors writing nondeterministic
 *       automata (multiple matching transitions) get the first one in
 *       declaration order; the verifier is intentionally not exploring
 *       all paths in this phase.</li>
 *   <li>Reaching an error state emits one violation per automaton even
 *       if the state is re-entered later — the goal is to surface every
 *       <em>distinct</em> failure, not every step that perpetuates it.</li>
 * </ul>
 *
 * <p>Priority 40 — last in the built-in chain. Cheaper checks
 * (allowlist 10, well-formed 20, capability 25, taint 30) run first;
 * the automaton walk is paid only for plans that survived everything
 * else.</p>
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
            walk(automaton, workflow, violations);
        }
        return VerificationResult.of(violations);
    }

    private void walk(SecurityAutomaton automaton,
                      Workflow workflow,
                      List<Violation> out) {
        Map<String, AutomatonState> stateByName = indexStates(automaton);
        String current = automaton.initialState();
        boolean errorEmitted = false;
        var steps = workflow.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            PlanNode node = step.node();
            if (!(node instanceof ToolCallNode call)) {
                continue;
            }
            AutomatonTransition match = findTransition(automaton, current, call.toolName());
            if (match == null) {
                continue;
            }
            current = match.toState();
            AutomatonState destination = stateByName.get(current);
            if (destination != null && destination.isError() && !errorEmitted) {
                out.add(new Violation(
                        CATEGORY,
                        "Automaton '" + automaton.name() + "' entered error state '"
                                + current + "' after step " + i + " ('"
                                + step.label() + "' calling '" + call.toolName() + "')",
                        "steps[" + i + "].toolName"));
                // One violation per automaton — re-entry into the error
                // state on later steps doesn't re-fire to keep the
                // diagnostic noise down. A second automaton in the
                // policy still produces its own independent violation.
                errorEmitted = true;
            }
        }
    }

    private static AutomatonTransition findTransition(SecurityAutomaton automaton,
                                                      String fromState,
                                                      String toolName) {
        for (AutomatonTransition t : automaton.transitions()) {
            if (t.fromState().equals(fromState) && t.toolName().equals(toolName)) {
                // Phase-5 minimum: ignore t.condition(); first match wins.
                return t;
            }
        }
        return null;
    }

    private static Map<String, AutomatonState> indexStates(SecurityAutomaton automaton) {
        var map = new HashMap<String, AutomatonState>(automaton.states().size());
        for (AutomatonState s : automaton.states()) {
            map.put(s.name(), s);
        }
        return map;
    }
}
