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

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.verifier.annotation.CapabilityScanner;
import org.atmosphere.verifier.annotation.RequiresCapability;
import org.atmosphere.verifier.checks.CapabilityVerifier;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityVerifierTest {

    private final CapabilityVerifier verifier = new CapabilityVerifier();

    static class CapTools {
        @AiTool(name = "fetch_emails", description = "fetch")
        @RequiresCapability({"net", "fs.read"})
        public String fetch(@Param("folder") String folder) {
            return "stub";
        }

        @AiTool(name = "summarize", description = "sum")
        public String summarize(@Param("input") String input) {
            return "stub";
        }
    }

    @Test
    void scanReadsRequiresCapabilityFromAiTools() {
        Map<String, Set<String>> req = CapabilityScanner.scan(CapTools.class);
        assertEquals(Set.of("net", "fs.read"), req.get("fetch_emails"));
        assertEquals(Set.of(), req.get("summarize"));
    }

    @Test
    void grantsSubsumeRequirementsPasses() {
        Policy policy = new Policy("p", Set.of(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                java.util.List.of(), java.util.List.of())
                .withCapabilities(
                        Set.of("net", "fs.read", "fs.write"),
                        CapabilityScanner.scan(CapTools.class));
        VerificationResult result = verifier.verify(
                PlanFixtures.okPlan(), policy,
                PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk(), () -> "violations: " + result.violations());
    }

    @Test
    void missingGrantIsRejected() {
        // The plan calls fetch_emails (needs "net" + "fs.read") but the
        // policy only grants "fs.read".
        Policy policy = new Policy("p", Set.of(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                java.util.List.of(), java.util.List.of())
                .withCapabilities(
                        Set.of("fs.read"),
                        CapabilityScanner.scan(CapTools.class));
        VerificationResult result = verifier.verify(
                PlanFixtures.okPlan(), policy,
                PlanFixtures.registryWithFixtureTools());
        assertFalse(result.isOk());
        Violation v = result.violations().get(0);
        assertEquals("capability", v.category());
        assertTrue(v.message().contains("fetch_emails"),
                () -> "msg: " + v.message());
        assertTrue(v.message().contains("net"),
                () -> "missing 'net' from msg: " + v.message());
        assertEquals("steps[0].toolName", v.astPath());
    }

    @Test
    void emptyRequirementsMapShortCircuits() {
        // No requirement map at all → no work, no violations regardless
        // of grants. Lets a Phase-1-style policy stay green.
        Policy policy = new Policy("p", Set.of(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                java.util.List.of(), java.util.List.of());
        VerificationResult result = verifier.verify(
                PlanFixtures.okPlan(), policy,
                PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk());
    }

    @Test
    void toolWithoutRequirementsAlwaysPasses() {
        // summarize has no required caps; the policy grants nothing.
        // The summarize step still passes because it asks for nothing.
        Map<String, Set<String>> reqs = Map.of("summarize", Set.of());
        Policy policy = new Policy("p", Set.of(PlanFixtures.SUMMARIZE),
                java.util.List.of(), java.util.List.of())
                .withCapabilities(Set.of(), reqs);
        var wf = new org.atmosphere.verifier.ast.Workflow(
                "echo",
                java.util.List.of(new org.atmosphere.verifier.ast.WorkflowStep(
                        "s", new org.atmosphere.verifier.ast.ToolCallNode(
                                PlanFixtures.SUMMARIZE, Map.of("input", "x"), "out"))));
        VerificationResult result = verifier.verify(
                wf, policy, PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk());
    }
}
