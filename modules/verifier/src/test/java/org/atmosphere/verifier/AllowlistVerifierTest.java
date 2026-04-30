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

import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.checks.AllowlistVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllowlistVerifierTest {

    private final AllowlistVerifier verifier = new AllowlistVerifier();

    @Test
    void okPlanPasses() {
        VerificationResult result = verifier.verify(
                PlanFixtures.okPlan(),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk(), () -> "violations: " + result.violations());
    }

    @Test
    void unknownToolIsRejected() {
        VerificationResult result = verifier.verify(
                PlanFixtures.unknownToolPlan(),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());
        assertFalse(result.isOk());
        assertEquals(1, result.violations().size());
        Violation v = result.violations().get(0);
        assertEquals("allowlist", v.category());
        assertTrue(v.message().contains("delete_database"), () -> "message: " + v.message());
        assertEquals("steps[0].toolName", v.astPath());
    }

    @Test
    void registryGapIsRejected() {
        // Policy permits send_email but the registry doesn't know about it
        // — deployment drift. The verifier surfaces both halves.
        Workflow wf = new Workflow(
                "send",
                List.of(new WorkflowStep("send", new org.atmosphere.verifier.ast.ToolCallNode(
                        PlanFixtures.SEND, java.util.Map.of("to", "alice@company.com"), null))));
        VerificationResult result = verifier.verify(
                wf,
                PlanFixtures.policyAllowing(PlanFixtures.SEND),
                PlanFixtures.registryMissing(PlanFixtures.SEND));
        assertFalse(result.isOk());
        Violation v = result.violations().get(0);
        assertEquals("allowlist", v.category());
        assertTrue(v.message().contains("not registered"), () -> "message: " + v.message());
    }

    @Test
    void emptyPlanIsTriviallyOk() {
        Workflow empty = new Workflow("nothing", List.of());
        VerificationResult result = verifier.verify(
                empty,
                PlanFixtures.policyAllowing(),
                PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk());
    }
}
