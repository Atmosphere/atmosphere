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
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.util.ArrayList;
import java.util.List;

/**
 * Asserts every {@link ToolCallNode} in the workflow names a tool that
 * (a) appears in {@link Policy#allowedTools()} and (b) is actually
 * registered with the supplied {@link ToolRegistry}.
 *
 * <p>The dual check matters: the policy might allow {@code send_email}
 * even though no implementation is registered (deployment drift), or a
 * tool might be registered but absent from the policy (drift the other
 * way). Both surface as violations — distinct categories, same family.</p>
 *
 * <p>Priority 10 — runs first so downstream verifiers don't waste effort
 * on a plan that already names disallowed or missing tools.</p>
 */
public final class AllowlistVerifier implements PlanVerifier {

    static final String CATEGORY = "allowlist";

    @Override
    public String name() {
        return "allowlist";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry) {
        List<Violation> violations = new ArrayList<>();
        var steps = workflow.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            PlanNode node = step.node();
            if (node instanceof ToolCallNode call) {
                checkOneCall(call, i, policy, registry, violations);
            }
            // Phase 5 control-flow nodes recurse into their bodies here.
        }
        return VerificationResult.of(violations);
    }

    private void checkOneCall(ToolCallNode call,
                              int stepIndex,
                              Policy policy,
                              ToolRegistry registry,
                              List<Violation> out) {
        String toolName = call.toolName();
        if (!policy.allowedTools().contains(toolName)) {
            out.add(new Violation(
                    CATEGORY,
                    "Tool '" + toolName + "' is not in policy allowlist "
                            + policy.allowedTools(),
                    "steps[" + stepIndex + "].toolName"));
            return;
        }
        if (registry.getTool(toolName).isEmpty()) {
            out.add(new Violation(
                    CATEGORY,
                    "Tool '" + toolName
                            + "' is allowed by policy but not registered "
                            + "with the ToolRegistry",
                    "steps[" + stepIndex + "].toolName"));
        }
    }
}
