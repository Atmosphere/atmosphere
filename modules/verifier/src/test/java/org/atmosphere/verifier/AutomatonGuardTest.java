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
import org.atmosphere.verifier.checks.AutomatonVerifier;
import org.atmosphere.verifier.policy.AutomatonState;
import org.atmosphere.verifier.policy.AutomatonTransition;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.SecurityAutomaton;
import org.atmosphere.verifier.spi.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guarded transitions: a transition fires only when its
 * {@link AutomatonTransition#condition()} can hold. When the guard's truth
 * is not statically known, the verifier soundly explores both the taken and
 * not-taken successors so it never misses an error a runtime value could
 * reach.
 */
class AutomatonGuardTest {

    private final AutomatonVerifier verifier = new AutomatonVerifier();

    /** ok --charge[amount > 1000]--> over(error). */
    private static SecurityAutomaton spendCap() {
        return new SecurityAutomaton("spend-cap",
                List.of(new AutomatonState("ok", false),
                        new AutomatonState("over", true)),
                List.of(new AutomatonTransition("ok", "over", "charge", "amount > 1000")),
                "ok");
    }

    private static Policy policy() {
        return new Policy("p", Set.of("charge"), List.of(), List.of(spendCap()));
    }

    private VerificationResult verifyCharge(Object amount) {
        Workflow wf = new Workflow("charge", List.of(
                new WorkflowStep("pay", new ToolCallNode(
                        "charge", Map.of("amount", amount), null))));
        return verifier.verify(wf, policy(), PlanFixtures.registryWithFixtureTools());
    }

    @Test
    void guardTrueOnLiteralReachesErrorState() {
        assertFalse(verifyCharge(2000).isOk(), "amount > 1000 must trip the spend cap");
    }

    @Test
    void guardFalseOnLiteralBlocksTheTransition() {
        assertTrue(verifyCharge(500).isOk(),
                () -> "amount <= 1000 must not transition to the error state");
    }

    @Test
    void unknownGuardSoundlyExploresBothSuccessors() {
        // The amount is a symbolic reference resolved only at run time, so
        // the guard is statically unknown — the verifier must consider the
        // error state reachable and refuse.
        VerificationResult result = verifyCharge(new SymRef("userAmount"));
        assertFalse(result.isOk(),
                () -> "unknown guard should be treated as possibly-true; got: "
                        + result.violations());
    }
}
