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
import org.atmosphere.verifier.ast.PlanNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.ControlFlowMode;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces the policy's {@link ControlFlowMode}: the pluggable gate that
 * decides whether a plan may contain {@link ConditionalNode} branches.
 *
 * <p>Under {@link ControlFlowMode#LINEAR_ONLY} (the default) any
 * control-flow node is a violation — the plan must be a flat list of tool
 * calls, which is what keeps the static proof covering the single sequence
 * that runs. Under {@link ControlFlowMode#BRANCHING} conditionals are
 * admitted and every other verifier descends into both arms.</p>
 *
 * <p>Priority 5 — runs before every other built-in verifier so a plan whose
 * very shape is disallowed is refused before the per-call checks walk it.
 * Because a nested conditional can only exist inside a top-level one, a
 * top-level scan is sufficient to detect the disallowed shape; the message
 * still names the offending step for the operator.</p>
 */
public final class StructureVerifier implements PlanVerifier {

    static final String CATEGORY = "control-flow";

    @Override
    public String name() {
        return "structure";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry) {
        if (policy.controlFlow() == ControlFlowMode.BRANCHING) {
            return VerificationResult.ok();
        }
        List<Violation> violations = new ArrayList<>();
        var steps = workflow.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            PlanNode node = step.node();
            if (node instanceof ConditionalNode) {
                violations.add(new Violation(
                        CATEGORY,
                        "Conditional step '" + step.label()
                                + "' is not permitted under a LINEAR_ONLY policy. "
                                + "Set the policy's control-flow mode to BRANCHING to "
                                + "admit data-dependent branches.",
                        "steps[" + i + "]"));
            }
        }
        return VerificationResult.of(violations);
    }
}
