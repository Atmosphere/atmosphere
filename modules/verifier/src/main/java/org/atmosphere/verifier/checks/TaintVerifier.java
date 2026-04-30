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
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.TaintRule;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static dataflow verifier — refuses any {@link Workflow} whose plan
 * routes data from a {@link TaintRule#sourceTool() source tool}'s output
 * into the {@link TaintRule#sinkParam() forbidden parameter} of the rule's
 * {@link TaintRule#sinkTool() sink tool}.
 *
 * <p>This is the headline correctness property Meijer's "Guardians of the
 * Agents" pattern delivers over plain ReAct loops: prompt-injection
 * attacks where an LLM is tricked into emailing the contents of an inbox
 * to an attacker reduce to a static dataflow rule that catches the bad
 * plan <em>before any tool fires</em>. Same mechanical reasoning that
 * makes parameterised SQL safe.</p>
 *
 * <h3>Algorithm</h3>
 * <p>Walk the workflow steps in order, tracking a per-binding set of the
 * source tools whose data could have flowed into that binding:</p>
 * <ol>
 *   <li><b>Sink check</b> — if the current call matches any
 *       {@link TaintRule#sinkTool()} and the call's
 *       {@link TaintRule#sinkParam() forbidden param} is a
 *       {@link SymRef} into a binding tainted by the rule's source,
 *       emit a violation.</li>
 *   <li><b>Propagate inbound</b> — union the taint of every SymRef
 *       argument's referenced binding into the call's outgoing taint
 *       set.</li>
 *   <li><b>Direct source</b> — if the call's tool itself is a source
 *       per any rule, add that source to the outgoing set.</li>
 *   <li><b>Bind</b> — if the call has a result binding, record the
 *       outgoing taint set under that binding so subsequent steps see
 *       the transitive flow.</li>
 * </ol>
 *
 * <p>The order matters: sink checks fire <em>before</em> propagation so a
 * tool that is itself a source for one rule and a sink for another (e.g.
 * a translation tool that reads sensitive content and emits transformed
 * content) still has its inbound taint checked against the sink rule.</p>
 *
 * <h3>Limitations (Phase 3)</h3>
 * <ul>
 *   <li>Shallow taint — a {@link SymRef} buried inside a list/map
 *       argument is not unwrapped. Same constraint as
 *       {@link org.atmosphere.verifier.execute.WorkflowExecutor}; the
 *       Phase 5 deep-resolution pass closes both gaps in lockstep.</li>
 *   <li>String concatenation / partial-data flows are not modelled —
 *       this verifier reasons over <em>references</em>, not over the
 *       contents of any value.</li>
 * </ul>
 *
 * <p>Priority 30 — runs after the cheaper allowlist (10) and
 * well-formedness (20) checks; their guarantees (every tool name is
 * known, every SymRef is bound) simplify the taint walk because we can
 * skip defensive checks on missing bindings.</p>
 */
public final class TaintVerifier implements PlanVerifier {

    static final String CATEGORY = "taint";

    @Override
    public String name() {
        return "taint";
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry) {
        if (policy.taintRules().isEmpty()) {
            // No rules = no work; cheap short-circuit so policies that
            // only declare an allowlist don't pay even the walk cost.
            return VerificationResult.ok();
        }
        var violations = new ArrayList<Violation>();
        var bindingTaint = new HashMap<String, Set<String>>();
        var steps = workflow.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            PlanNode node = step.node();
            if (node instanceof ToolCallNode call) {
                processCall(call, i, policy.taintRules(), bindingTaint, violations);
            }
            // Phase 5 control-flow nodes recurse into their bodies here.
        }
        return VerificationResult.of(violations);
    }

    private void processCall(ToolCallNode call,
                             int stepIndex,
                             List<TaintRule> rules,
                             Map<String, Set<String>> bindingTaint,
                             List<Violation> violations) {
        // Step 1 — sink check. Examine each rule whose sinkTool matches
        // this call's tool. If the call's argument map carries a SymRef
        // into a tainted binding at the rule's sinkParam, emit a
        // violation. We do this BEFORE propagation so a tool that is
        // both a source for one rule and a sink for another is checked
        // on its inbound flow first.
        for (TaintRule rule : rules) {
            if (!call.toolName().equals(rule.sinkTool())) {
                continue;
            }
            Object sinkValue = call.arguments().get(rule.sinkParam());
            if (!(sinkValue instanceof SymRef ref)) {
                continue;
            }
            Set<String> taintsOnSink = bindingTaint.getOrDefault(ref.ref(), Set.of());
            if (taintsOnSink.contains(rule.sourceTool())) {
                violations.add(new Violation(
                        CATEGORY,
                        "Tainted dataflow from '" + rule.sourceTool()
                                + "' reaches '" + call.toolName() + "."
                                + rule.sinkParam() + "' (rule '"
                                + rule.name() + "', via @" + ref.ref() + ")",
                        "steps[" + stepIndex + "].arguments." + rule.sinkParam()));
            }
        }

        // Step 2 — compute outgoing taint set: the union of the taint
        // sets of every SymRef arg's referenced binding plus any direct
        // source rule whose sourceTool matches this call. The result
        // becomes the taint set of this call's resultBinding.
        Set<String> outgoing = new HashSet<>();
        for (Object value : call.arguments().values()) {
            if (value instanceof SymRef ref) {
                outgoing.addAll(bindingTaint.getOrDefault(ref.ref(), Set.of()));
            }
        }
        for (TaintRule rule : rules) {
            if (call.toolName().equals(rule.sourceTool())) {
                outgoing.add(rule.sourceTool());
            }
        }

        // Step 3 — record under the result binding if any. Defensive
        // copy + immutable is overkill here (HashMap value, single
        // owner) so we keep the live HashSet for downstream union.
        if (call.hasResultBinding()) {
            bindingTaint.put(call.resultBinding(), outgoing);
        }
    }
}
