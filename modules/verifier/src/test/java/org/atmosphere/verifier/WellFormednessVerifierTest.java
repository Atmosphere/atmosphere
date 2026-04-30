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

import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.checks.WellFormednessVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WellFormednessVerifierTest {

    private final WellFormednessVerifier verifier = new WellFormednessVerifier();

    @Test
    void forwardOnlyRefsPass() {
        VerificationResult result = verifier.verify(
                PlanFixtures.okPlan(),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk(), () -> "violations: " + result.violations());
    }

    @Test
    void forwardReferenceIsRejected() {
        VerificationResult result = verifier.verify(
                PlanFixtures.forwardRefPlan(),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());
        assertFalse(result.isOk());
        assertEquals(1, result.violations().size());
        Violation v = result.violations().get(0);
        assertEquals("well-formed", v.category());
        assertTrue(v.message().contains("emails"), () -> "message: " + v.message());
        assertEquals("steps[0].arguments.input", v.astPath());
    }

    @Test
    void literalOnlyArgumentsPass() {
        Workflow wf = new Workflow(
                "fetch only",
                List.of(new WorkflowStep("fetch",
                        new ToolCallNode(PlanFixtures.FETCH, Map.of("folder", "inbox"), "x"))));
        VerificationResult result = verifier.verify(
                wf,
                PlanFixtures.policyAllowing(PlanFixtures.FETCH),
                PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk());
    }

    @Test
    void selfReferencingStepIsRejected() {
        // A step referencing its own to-be-bound name is a forward
        // reference: at the time we evaluate the args, that binding
        // hasn't been produced yet.
        Workflow wf = new Workflow(
                "self ref",
                List.of(new WorkflowStep("loop",
                        new ToolCallNode(PlanFixtures.SUMMARIZE,
                                Map.of("input", new SymRef("summary")),
                                "summary"))));
        VerificationResult result = verifier.verify(
                wf,
                PlanFixtures.policyAllowing(PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());
        assertFalse(result.isOk());
        assertEquals("well-formed", result.violations().get(0).category());
    }
}
