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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the native Z3 backend. Metadata assertions always run; the proof and
 * resolution assertions are gated on the Z3 natives being present on
 * {@code java.library.path} (they run on machines/CI lanes that provide
 * {@code libz3}/{@code libz3java}, and skip cleanly elsewhere — this is an
 * environment gate, not a silent always-skip).
 */
class Z3SmtCheckerTest {

    private final Z3SmtChecker z3 = new Z3SmtChecker();

    @Test
    void metadataIdentifiesZ3AtHigherPriorityThanSmtInterpol() {
        assertEquals("z3", z3.name());
        assertEquals(200, z3.priority());
        assertTrue(z3.priority() > new SmtInterpolChecker().priority(),
                "Z3 must outrank SMTInterpol so resolve() prefers it when present");
    }

    @Test
    void z3ProvesAndFlagsIdenticallyToSmtInterpolWhenAvailable() {
        assumeTrue(z3.isAvailable(), "Z3 natives not on java.library.path — skipping native proof");
        var inv = new NumericInvariant("transfer", "amount",
                NumericInvariant.Op.LE, new NumericInvariant.RefBound("balance"));

        VerificationResult safe = z3.check(
                workflow("transfer", Map.of("amount", new SymRef("balance"))), policyWith(inv));
        assertTrue(safe.isOk(), () -> "Z3 should prove pass-through safe, got: " + safe.violations());

        VerificationResult unsafe = z3.check(
                workflow("transfer", Map.of("amount", new SymRef("userInput"))), policyWith(inv));
        assertFalse(unsafe.isOk(), "Z3 should flag an unconstrained symbol");
        assertEquals(1, unsafe.violations().size());
    }

    @Test
    void resolvePrefersZ3WhenItsNativesArePresent() {
        assumeTrue(new Z3SmtChecker().isAvailable(), "Z3 natives absent — resolve() falls back to SMTInterpol");
        assertEquals("z3", SmtChecker.resolve().name(),
                "with Z3 available, resolve() must pick it (priority 200) over SMTInterpol (100)");
    }

    private static Workflow workflow(String tool, Map<String, Object> args) {
        return new Workflow("goal", List.of(new WorkflowStep("s", new ToolCallNode(tool, args, null))));
    }

    private static Policy policyWith(NumericInvariant inv) {
        return new Policy("p", Set.of("transfer"), List.of(), List.of(),
                Set.of(), Map.of(), List.of(inv));
    }
}
