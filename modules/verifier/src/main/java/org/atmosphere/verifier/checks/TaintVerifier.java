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
 *       {@link TaintRule#sinkTool()} and a {@link org.atmosphere.verifier.ast.SymRef}
 *       at <em>any depth</em> inside the call's
 *       {@link TaintRule#sinkParam() forbidden param} points at a binding
 *       tainted by the rule's source, emit a violation.</li>
 *   <li><b>Propagate inbound</b> — union the taint of every SymRef
 *       argument (at any depth) into the call's outgoing taint set.</li>
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
 * <h3>Conditional branches</h3>
 * <p>Each arm of a {@link ConditionalNode} is walked over a copy of the
 * taint state at the branch point; the taint produced by either arm is
 * unioned back into the post-branch state. A sink reached in either arm is
 * a violation — the predicate that selects an arm is never trusted to keep
 * a leak from firing.</p>
 *
 * <p>This verifier reasons over symbolic <em>references</em>, not over the
 * contents of any value: the plan AST has no string-concatenation or
 * arithmetic nodes, so there is no sub-value flow for a reference-level
 * analysis to miss.</p>
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
        taintSteps(workflow.steps(), policy.taintRules(), bindingTaint, "steps", violations);
        return VerificationResult.of(violations);
    }

    /**
     * Walk a step list, mutating {@code bindingTaint} in place. After a
     * conditional, {@code bindingTaint} holds the union of the taint each
     * arm produced (conservative: a binding tainted on either path is
     * tainted afterward).
     */
    private void taintSteps(List<WorkflowStep> steps,
                            List<TaintRule> rules,
                            Map<String, Set<String>> bindingTaint,
                            String prefix,
                            List<Violation> violations) {
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            String stepPath = prefix + "[" + i + "]";
            var node = step.node();
            if (node instanceof ToolCallNode call) {
                processCall(call, stepPath, rules, bindingTaint, violations);
            } else if (node instanceof ConditionalNode cond) {
                Map<String, Set<String>> thenTaint = deepCopy(bindingTaint);
                taintSteps(cond.thenSteps(), rules, thenTaint, stepPath + ".then", violations);
                Map<String, Set<String>> elseTaint = deepCopy(bindingTaint);
                taintSteps(cond.elseSteps(), rules, elseTaint, stepPath + ".otherwise", violations);
                mergeInto(bindingTaint, thenTaint);
                mergeInto(bindingTaint, elseTaint);
            }
        }
    }

    private void processCall(ToolCallNode call,
                             String stepPath,
                             List<TaintRule> rules,
                             Map<String, Set<String>> bindingTaint,
                             List<Violation> violations) {
        // Step 1 — sink check. For each rule whose sinkTool matches this
        // call, inspect the forbidden parameter's value for a SymRef (at
        // any depth) into a binding tainted by the rule's source. Done
        // BEFORE propagation so a tool that is both a source for one rule
        // and a sink for another is checked on its inbound flow first.
        for (TaintRule rule : rules) {
            if (!call.toolName().equals(rule.sinkTool())) {
                continue;
            }
            Object sinkValue = call.arguments().get(rule.sinkParam());
            if (sinkValue == null) {
                continue;
            }
            String basePath = stepPath + ".arguments." + rule.sinkParam();
            SymRefs.forEach(sinkValue, basePath, (ref, path) -> {
                Set<String> taintsOnSink = bindingTaint.getOrDefault(ref.ref(), Set.of());
                if (taintsOnSink.contains(rule.sourceTool())) {
                    violations.add(new Violation(
                            CATEGORY,
                            "Tainted dataflow from '" + rule.sourceTool()
                                    + "' reaches '" + call.toolName() + "."
                                    + rule.sinkParam() + "' (rule '"
                                    + rule.name() + "', via @" + ref.ref() + ")",
                            path));
                }
            });
        }

        // Step 2 — compute outgoing taint: the union of the taint sets of
        // every SymRef argument (at any depth) plus any direct source rule
        // whose sourceTool matches this call. The result becomes the taint
        // set of this call's resultBinding.
        Set<String> outgoing = new HashSet<>();
        for (Object value : call.arguments().values()) {
            SymRefs.forEach(value, "", (ref, path) ->
                    outgoing.addAll(bindingTaint.getOrDefault(ref.ref(), Set.of())));
        }
        for (TaintRule rule : rules) {
            if (call.toolName().equals(rule.sourceTool())) {
                outgoing.add(rule.sourceTool());
            }
        }

        // Step 3 — record under the result binding if any.
        if (call.hasResultBinding()) {
            bindingTaint.put(call.resultBinding(), outgoing);
        }
    }

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> src) {
        var copy = new HashMap<String, Set<String>>(src.size());
        for (var entry : src.entrySet()) {
            copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return copy;
    }

    private static void mergeInto(Map<String, Set<String>> target,
                                  Map<String, Set<String>> src) {
        for (var entry : src.entrySet()) {
            target.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                    .addAll(entry.getValue());
        }
    }
}
