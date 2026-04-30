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
import org.atmosphere.verifier.checks.SmtVerifier;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.SmtChecker;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtCheckerTest {

    @Test
    void noOpCheckerAlwaysReportsGreen() {
        var checker = new SmtChecker.NoOpSmtChecker();
        VerificationResult result = checker.check(
                PlanFixtures.okPlan(), PlanFixtures.policyAllowing(PlanFixtures.FETCH));
        assertTrue(result.isOk());
    }

    @Test
    void resolveFallsBackToNoOpWhenNothingElseRegistered() {
        // No SmtChecker is registered via META-INF/services in this
        // module; resolve() returns the no-op fallback.
        SmtChecker resolved = SmtChecker.resolve();
        assertNotNull(resolved);
        assertEquals("noop", resolved.name());
        assertTrue(resolved.isAvailable());
    }

    @Test
    void smtVerifierAdaptsCheckerIntoPlanVerifierChain() {
        var verifier = new SmtVerifier(new SmtChecker.NoOpSmtChecker());
        VerificationResult result = verifier.verify(
                PlanFixtures.okPlan(),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());
        assertTrue(result.isOk());
        assertEquals("smt", verifier.name());
        assertEquals(200, verifier.priority(),
                "SmtVerifier must run after every cheaper check");
    }

    @Test
    void customCheckerViolationsPropagateThroughVerifier() {
        // Stub a checker that always returns a single violation.
        SmtChecker stub = new SmtChecker() {
            @Override public String name() { return "stub"; }
            @Override public boolean isAvailable() { return true; }
            @Override public VerificationResult check(Workflow w, Policy p) {
                return VerificationResult.of(
                        Violation.of("smt", "proof failed: cost > budget"));
            }
        };
        var verifier = new SmtVerifier(stub);
        VerificationResult result = verifier.verify(
                PlanFixtures.okPlan(),
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());
        assertFalse(result.isOk());
        assertEquals(1, result.violations().size());
        assertEquals("smt", result.violations().get(0).category());
    }

    @Test
    void defaultConstructorResolvesViaServiceLoader() {
        // No registered checker = no-op via resolve(); the default
        // constructor never throws.
        var verifier = new SmtVerifier();
        assertNotNull(verifier.checker());
        assertTrue(List.of("noop").contains(verifier.checker().name()),
                () -> "unexpected resolved checker: " + verifier.checker().name());
    }
}
