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
package org.atmosphere.verifier.smt;

import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.NumericInvariant;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.SmtChecker;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SmtInterpolChecker} against real SMTInterpol proofs. Each
 * test drives a genuine SAT/UNSAT decision — the assertions verify the proof
 * outcome (proven vs. counterexample), not merely that the checker ran.
 */
class SmtInterpolCheckerTest {

    private final SmtInterpolChecker checker = new SmtInterpolChecker();

    /** Build a single-step workflow invoking {@code tool} with {@code args}. */
    private static Workflow workflow(String tool, Map<String, Object> args) {
        var node = new ToolCallNode(tool, args, null);
        return new Workflow("test-goal", List.of(new WorkflowStep("s1", node)));
    }

    /** Policy carrying exactly one numeric invariant. */
    private static Policy policyWith(NumericInvariant inv) {
        return new Policy("smt-policy", Set.of(), List.of(), List.of())
                .withNumericInvariants(List.of(inv));
    }

    @Test
    void provenSafeWhenArgPassesBindingThrough() {
        // transfer.amount <= ref(balance); plan binds amount := SymRef(balance).
        // Both sides map to the same SMT variable, so amount <= balance is
        // valid for all runtime values (negation is UNSAT).
        var inv = new NumericInvariant("transfer", "amount",
                NumericInvariant.Op.LE, new NumericInvariant.RefBound("balance"));
        var wf = workflow("transfer", Map.of("amount", new SymRef("balance")));

        VerificationResult result = checker.check(wf, policyWith(inv));

        assertTrue(result.isOk(),
                () -> "expected proven (UNSAT), got: " + result.violations());
    }

    @Test
    void flaggedWhenArgIsUnconstrainedSymbol() {
        // Same invariant, but amount := SymRef(userInput): an unrelated symbol.
        // There is a runtime assignment with userInput > balance, so the plan
        // cannot guarantee the invariant (negation is SAT).
        var inv = new NumericInvariant("transfer", "amount",
                NumericInvariant.Op.LE, new NumericInvariant.RefBound("balance"));
        var wf = workflow("transfer", Map.of("amount", new SymRef("userInput")));

        VerificationResult result = checker.check(wf, policyWith(inv));

        assertFalse(result.isOk(), "expected a counterexample (SAT)");
        assertEquals(1, result.violations().size());
        Violation v = result.violations().get(0);
        assertEquals("smt", v.category());
        assertEquals("transfer.amount", v.astPath());
    }

    @Test
    void literalBoundSatisfied() {
        // transfer.amount <= 1000; amount := 500 (literal). Proven.
        var inv = new NumericInvariant("transfer", "amount",
                NumericInvariant.Op.LE, new NumericInvariant.LiteralBound(1000));
        var wf = workflow("transfer", Map.of("amount", 500));

        VerificationResult result = checker.check(wf, policyWith(inv));

        assertTrue(result.isOk(),
                () -> "500 <= 1000 should be proven, got: " + result.violations());
    }

    @Test
    void literalBoundViolated() {
        // transfer.amount <= 1000; amount := 5000 (literal). 5000 > 1000, so
        // the invariant is false: the negation is SAT (a constant model).
        var inv = new NumericInvariant("transfer", "amount",
                NumericInvariant.Op.LE, new NumericInvariant.LiteralBound(1000));
        var wf = workflow("transfer", Map.of("amount", 5000));

        VerificationResult result = checker.check(wf, policyWith(inv));

        assertFalse(result.isOk(), "5000 <= 1000 must be flagged");
        assertEquals("smt", result.violations().get(0).category());
    }

    @Test
    void greaterOrEqualProvenAndUnproven() {
        // GE proven: amount >= ref(floor) when amount := SymRef(floor).
        var provenInv = new NumericInvariant("withdraw", "amount",
                NumericInvariant.Op.GE, new NumericInvariant.RefBound("floor"));
        var provenWf = workflow("withdraw", Map.of("amount", new SymRef("floor")));
        assertTrue(checker.check(provenWf, policyWith(provenInv)).isOk(),
                "amount >= floor with amount := floor should be proven");

        // GE unproven: amount >= 100 when amount is an unconstrained symbol.
        var unprovenInv = new NumericInvariant("withdraw", "amount",
                NumericInvariant.Op.GE, new NumericInvariant.LiteralBound(100));
        var unprovenWf = workflow("withdraw", Map.of("amount", new SymRef("anything")));
        VerificationResult bad = checker.check(unprovenWf, policyWith(unprovenInv));
        assertFalse(bad.isOk(), "amount >= 100 for a free symbol is not provable");
        assertEquals("smt", bad.violations().get(0).category());
    }

    @Test
    void equalityProvenAndUnproven() {
        // EQ proven: amount = ref(quota) when amount := SymRef(quota).
        var provenInv = new NumericInvariant("grant", "amount",
                NumericInvariant.Op.EQ, new NumericInvariant.RefBound("quota"));
        var provenWf = workflow("grant", Map.of("amount", new SymRef("quota")));
        assertTrue(checker.check(provenWf, policyWith(provenInv)).isOk(),
                "amount = quota with amount := quota should be proven");

        // EQ unproven: amount = 42 when amount is an unconstrained symbol.
        var unprovenInv = new NumericInvariant("grant", "amount",
                NumericInvariant.Op.EQ, new NumericInvariant.LiteralBound(42));
        var unprovenWf = workflow("grant", Map.of("amount", new SymRef("free")));
        assertFalse(checker.check(unprovenWf, policyWith(unprovenInv)).isOk(),
                "amount = 42 for a free symbol is not provable");
    }

    @Test
    void isAvailableTrue() {
        assertTrue(new SmtInterpolChecker().isAvailable(),
                "SMTInterpol (pure-JVM) must load on this machine");
    }

    @Test
    void resolvePicksSmtInterpolOverNoOp() {
        SmtChecker resolved = SmtChecker.resolve();
        assertEquals("smtinterpol", resolved.name(),
                "priority-100 SmtInterpolChecker must win over priority-0 NoOp");
    }
}
