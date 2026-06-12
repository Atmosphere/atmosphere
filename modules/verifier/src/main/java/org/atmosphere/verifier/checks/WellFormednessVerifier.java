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
import org.atmosphere.verifier.policy.Condition;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Asserts every {@link org.atmosphere.verifier.ast.SymRef} in the workflow
 * — at any argument depth — points at a binding produced by an earlier
 * step, and that every conditional predicate references only bound
 * variables. Forward references and dangling refs both fail with a
 * {@code "well-formed"} violation, keyed to the offending node's AST path.
 *
 * <p>This is the contract the {@link org.atmosphere.verifier.execute.WorkflowExecutor}
 * relies on — well-formed plans never trigger
 * {@link org.atmosphere.verifier.execute.UnresolvedSymRefException} at
 * runtime.</p>
 *
 * <h3>Conditional scoping</h3>
 * <p>A {@link ConditionalNode}'s predicate is checked against the scope at
 * the branch point. Each arm is then verified in its own copy of that
 * scope, so a binding produced inside one arm is not visible in the other.
 * Only bindings produced on <em>both</em> arms survive past the conditional
 * — anything bound on a single path could be undefined at run time
 * depending on which arm the predicate selects.</p>
 *
 * <p>Priority 20 — runs after the allowlist check so dangling refs are
 * only reported on otherwise-permitted plans.</p>
 */
public final class WellFormednessVerifier implements PlanVerifier {

    static final String CATEGORY = "well-formed";

    @Override
    public String name() {
        return "well-formed";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry) {
        List<Violation> violations = new ArrayList<>();
        verifySteps(workflow.steps(), new HashSet<>(), "steps", violations);
        return VerificationResult.of(violations);
    }

    /**
     * Verify a step list, returning the set of bindings guaranteed in scope
     * after it runs.
     */
    private Set<String> verifySteps(List<WorkflowStep> steps,
                                    Set<String> scopeIn,
                                    String prefix,
                                    List<Violation> out) {
        Set<String> scope = new HashSet<>(scopeIn);
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            String stepPath = prefix + "[" + i + "]";
            var node = step.node();
            if (node instanceof ToolCallNode call) {
                checkRefsInScope(call, stepPath, scope, out);
                if (call.hasResultBinding()) {
                    scope.add(call.resultBinding());
                }
            } else if (node instanceof ConditionalNode cond) {
                checkPredicateInScope(cond, stepPath, scope, out);
                Set<String> thenOut = verifySteps(
                        cond.thenSteps(), scope, stepPath + ".then", out);
                Set<String> elseOut = verifySteps(
                        cond.elseSteps(), scope, stepPath + ".otherwise", out);
                // Only bindings produced on both arms are guaranteed past
                // the branch; the predicate decides which arm runs.
                thenOut.retainAll(elseOut);
                scope = thenOut;
            }
        }
        return scope;
    }

    private void checkRefsInScope(ToolCallNode call,
                                  String stepPath,
                                  Set<String> scope,
                                  List<Violation> out) {
        for (var entry : call.arguments().entrySet()) {
            String basePath = stepPath + ".arguments." + entry.getKey();
            SymRefs.forEach(entry.getValue(), basePath, (ref, path) -> {
                if (!scope.contains(ref.ref())) {
                    out.add(new Violation(
                            CATEGORY,
                            "SymRef '" + ref.ref() + "' is not in scope at "
                                    + stepPath + " (only "
                                    + (scope.isEmpty() ? "no" : scope)
                                    + " bindings are bound earlier)",
                            path));
                }
            });
        }
    }

    private void checkPredicateInScope(ConditionalNode cond,
                                       String stepPath,
                                       Set<String> scope,
                                       List<Violation> out) {
        Set<String> names;
        try {
            names = Condition.parse(cond.predicate()).referencedNames();
        } catch (IllegalArgumentException ex) {
            out.add(new Violation(
                    CATEGORY,
                    "Malformed conditional predicate at " + stepPath + ": "
                            + ex.getMessage(),
                    stepPath + ".predicate"));
            return;
        }
        for (String n : names) {
            if (!scope.contains(n)) {
                out.add(new Violation(
                        CATEGORY,
                        "Predicate variable '" + n + "' is not in scope at "
                                + stepPath + " (only "
                                + (scope.isEmpty() ? "no" : scope)
                                + " bindings are bound earlier)",
                        stepPath + ".predicate"));
            }
        }
    }
}
