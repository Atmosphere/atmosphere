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
package org.atmosphere.verifier;

import org.atmosphere.verifier.ast.ConditionalNode;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.checks.StructureVerifier;
import org.atmosphere.verifier.policy.ControlFlowMode;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureVerifierTest {

    private final StructureVerifier verifier = new StructureVerifier();

    private static Workflow conditionalPlan() {
        return new Workflow("branchy", List.of(
                new WorkflowStep("decide", new ConditionalNode(
                        "score >= 80",
                        List.of(new WorkflowStep("hi", new ToolCallNode(
                                PlanFixtures.FETCH, Map.of("folder", "inbox"), "x"))),
                        List.of()))));
    }

    private static Workflow linearPlan() {
        return new Workflow("flat", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of("folder", "inbox"), "x"))));
    }

    @Test
    void conditionalIsRefusedUnderLinearOnly() {
        Policy policy = PlanFixtures.policyAllowing(PlanFixtures.FETCH); // LINEAR_ONLY default
        VerificationResult result = verifier.verify(
                conditionalPlan(), policy, PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals(1, result.violations().size());
        Violation v = result.violations().get(0);
        assertEquals("control-flow", v.category());
        assertEquals("steps[0]", v.astPath());
    }

    @Test
    void conditionalIsAdmittedUnderBranching() {
        Policy policy = PlanFixtures.policyAllowing(PlanFixtures.FETCH)
                .withControlFlow(ControlFlowMode.BRANCHING);
        VerificationResult result = verifier.verify(
                conditionalPlan(), policy, PlanFixtures.registryWithFixtureTools());

        assertTrue(result.isOk(), () -> "violations: " + result.violations());
    }

    @Test
    void linearPlanPassesEitherMode() {
        Policy linear = PlanFixtures.policyAllowing(PlanFixtures.FETCH);
        Policy branching = linear.withControlFlow(ControlFlowMode.BRANCHING);
        var registry = PlanFixtures.registryWithFixtureTools();

        assertTrue(verifier.verify(linearPlan(), linear, registry).isOk());
        assertTrue(verifier.verify(linearPlan(), branching, registry).isOk());
    }

    @Test
    void defaultPolicyModeIsLinearOnly() {
        assertEquals(ControlFlowMode.LINEAR_ONLY,
                PlanFixtures.policyAllowing(PlanFixtures.FETCH).controlFlow());
    }
}
