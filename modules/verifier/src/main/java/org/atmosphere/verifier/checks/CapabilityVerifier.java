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
import java.util.Set;
import java.util.TreeSet;

/**
 * Asserts every {@link ToolCallNode} in the workflow names a tool whose
 * declared capability requirements are a subset of the policy's
 * {@link Policy#grantedCapabilities()}. The classic least-authority
 * check in code form: a tool that needs {@code "net"} cannot fire under
 * a policy that only granted {@code "fs.read"}.
 *
 * <p>Tools without an entry in
 * {@link Policy#toolCapabilityRequirements()} are treated as requiring
 * no capabilities — there is no implicit denial. Authors who want
 * deny-by-default explicitly populate the requirement map (typically
 * via
 * {@link org.atmosphere.verifier.annotation.CapabilityScanner#scan}).</p>
 *
 * <p>Priority 25 — between well-formedness (20) and taint (30). The
 * cheap structural check skips plans that already failed the basics;
 * the more expensive taint walk skips plans that won't even be allowed
 * to fire.</p>
 */
public final class CapabilityVerifier implements PlanVerifier {

    static final String CATEGORY = "capability";

    @Override
    public String name() {
        return "capability";
    }

    @Override
    public int priority() {
        return 25;
    }

    @Override
    public VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry) {
        if (policy.toolCapabilityRequirements().isEmpty()) {
            // No requirements declared = no work. Cheap short-circuit
            // for policies that don't opt into capability checking.
            return VerificationResult.ok();
        }
        List<Violation> violations = new ArrayList<>();
        Set<String> grants = policy.grantedCapabilities();
        var steps = workflow.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            PlanNode node = step.node();
            if (node instanceof ToolCallNode call) {
                checkOneCall(call, i, grants, policy, violations);
            }
        }
        return VerificationResult.of(violations);
    }

    private void checkOneCall(ToolCallNode call,
                              int stepIndex,
                              Set<String> grants,
                              Policy policy,
                              List<Violation> out) {
        Set<String> required = policy.toolCapabilityRequirements()
                .getOrDefault(call.toolName(), Set.of());
        if (required.isEmpty()) {
            return;
        }
        // Use TreeSet for deterministic violation message ordering —
        // important because the message is part of the public diagnostic
        // surface and snapshot tests would flake on HashSet ordering.
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(grants);
        if (missing.isEmpty()) {
            return;
        }
        out.add(new Violation(
                CATEGORY,
                "Tool '" + call.toolName() + "' requires capabilities "
                        + new TreeSet<>(required) + " but the policy only grants "
                        + new TreeSet<>(grants) + " (missing: " + missing + ")",
                "steps[" + stepIndex + "].toolName"));
    }
}
