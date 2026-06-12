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

import org.atmosphere.verifier.ast.ConditionalNode;
import org.atmosphere.verifier.ast.PlanNode;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.WorkflowStep;

import java.util.List;

/**
 * Shared, order-insensitive traversal of every {@link ToolCallNode} in a
 * step list — descending into both arms of every {@link ConditionalNode}.
 * Used by the verifiers whose check is per-call and independent of control
 * flow (allowlist, capability); the scope- and state-sensitive verifiers
 * (well-formedness, taint, automaton) recurse themselves so they can fork
 * and merge branch context.
 *
 * <p>The {@code stepPath} handed to the visitor is the AST path of the
 * enclosing step (e.g. {@code "steps[1]"} or
 * {@code "steps[1].then[0]"}), so callers append {@code ".toolName"} or
 * {@code ".arguments.<name>"} to point a {@code Violation} at the exact
 * offending node.</p>
 */
final class PlanWalk {

    @FunctionalInterface
    interface CallVisitor {
        void visit(ToolCallNode call, String stepPath);
    }

    private PlanWalk() {
    }

    static void forEachCall(List<WorkflowStep> steps, CallVisitor visitor) {
        walk(steps, "steps", visitor);
    }

    private static void walk(List<WorkflowStep> steps, String prefix, CallVisitor visitor) {
        for (int i = 0; i < steps.size(); i++) {
            PlanNode node = steps.get(i).node();
            String stepPath = prefix + "[" + i + "]";
            if (node instanceof ToolCallNode call) {
                visitor.visit(call, stepPath);
            } else if (node instanceof ConditionalNode cond) {
                walk(cond.thenSteps(), stepPath + ".then", visitor);
                walk(cond.elseSteps(), stepPath + ".otherwise", visitor);
            }
        }
    }
}
