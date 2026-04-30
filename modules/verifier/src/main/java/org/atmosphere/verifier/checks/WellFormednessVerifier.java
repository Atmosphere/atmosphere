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
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Asserts every {@link SymRef} in the workflow points at a binding
 * produced by an earlier step. Forward references and dangling refs both
 * fail with a {@code "well-formed"} violation, keyed to the offending
 * argument's AST path.
 *
 * <p>This is the contract the {@link org.atmosphere.verifier.execute.WorkflowExecutor}
 * relies on — well-formed plans never trigger
 * {@link org.atmosphere.verifier.execute.UnresolvedSymRefException} at
 * runtime.</p>
 *
 * <p>Priority 20 — runs after the allowlist check so dangling refs are
 * only reported on otherwise-permitted plans.</p>
 *
 * <p><strong>Phase 1 scope</strong>: shallow scan (top-level argument
 * map values). Phase 5 widens to nested SymRefs once the executor's
 * deep-resolution pass lands.</p>
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
        Set<String> bindingsInScope = new HashSet<>();

        var steps = workflow.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            PlanNode node = step.node();
            if (node instanceof ToolCallNode call) {
                checkRefsInScope(call, i, bindingsInScope, violations);
                if (call.hasResultBinding()) {
                    bindingsInScope.add(call.resultBinding());
                }
            }
            // Phase 5 control-flow nodes scope-recurse here.
        }
        return VerificationResult.of(violations);
    }

    private void checkRefsInScope(ToolCallNode call,
                                  int stepIndex,
                                  Set<String> bindingsInScope,
                                  List<Violation> out) {
        for (var entry : call.arguments().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof SymRef ref && !bindingsInScope.contains(ref.ref())) {
                out.add(new Violation(
                        CATEGORY,
                        "SymRef '" + ref.ref() + "' is not in scope at step "
                                + stepIndex + " (only "
                                + (bindingsInScope.isEmpty() ? "no" : bindingsInScope)
                                + " bindings are bound earlier)",
                        "steps[" + stepIndex + "].arguments." + entry.getKey()));
            }
        }
    }
}
