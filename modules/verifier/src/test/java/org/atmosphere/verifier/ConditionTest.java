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
import org.atmosphere.verifier.policy.Condition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTest {

    @Test
    void runtimeNumericOrdering() {
        Condition c = Condition.parse("score >= 80");
        assertTrue(c.evaluate(Map.of("score", 90)));
        assertFalse(c.evaluate(Map.of("score", 70)));
    }

    @Test
    void runtimeStringEquality() {
        Condition c = Condition.parse("status == approved");
        assertTrue(c.evaluate(Map.of("status", "approved")));
        assertFalse(c.evaluate(Map.of("status", "rejected")));
        assertTrue(Condition.parse("status != approved")
                .evaluate(Map.of("status", "rejected")));
    }

    @Test
    void numericAndStringNumberCompareEqual() {
        // A numeric literal and a numeric string compare numerically.
        assertTrue(Condition.parse("n == 5").evaluate(Map.of("n", 5)));
        assertTrue(Condition.parse("n == 5").evaluate(Map.of("n", "5")));
    }

    @Test
    void rhsVariableReference() {
        Condition c = Condition.parse("a == @b");
        assertEquals(java.util.Set.of("a", "b"), c.referencedNames());
        assertTrue(c.evaluate(Map.of("a", "x", "b", "x")));
        assertFalse(c.evaluate(Map.of("a", "x", "b", "y")));
    }

    @Test
    void bareRhsWordIsStringLiteralNotVariable() {
        // 'approved' on the right is a literal, so only 'status' is read.
        assertEquals(java.util.Set.of("status"),
                Condition.parse("status == approved").referencedNames());
    }

    @Test
    void staticEvalKnownLiteralsDecide() {
        Condition c = Condition.parse("amount > 1000");
        assertEquals(Condition.Tristate.TRUE, c.evaluateStatic(Map.of("amount", 2000)));
        assertEquals(Condition.Tristate.FALSE, c.evaluateStatic(Map.of("amount", 500)));
    }

    @Test
    void staticEvalUnknownWhenOperandIsSymbolic() {
        Condition c = Condition.parse("amount > 1000");
        // A SymRef is resolved only at run time — statically unknown.
        assertEquals(Condition.Tristate.UNKNOWN,
                c.evaluateStatic(Map.of("amount", new SymRef("x"))));
        // Absent variable is likewise unknown.
        assertEquals(Condition.Tristate.UNKNOWN, c.evaluateStatic(Map.of()));
    }

    @Test
    void unboundVariableAtRuntimeThrows() {
        Condition c = Condition.parse("score >= 80");
        assertThrows(IllegalArgumentException.class, () -> c.evaluate(Map.of()));
    }

    @Test
    void orderingComparisonOnNonNumericThrowsAtRuntime() {
        Condition c = Condition.parse("name > 10");
        assertThrows(IllegalArgumentException.class,
                () -> c.evaluate(Map.of("name", "alice")));
    }

    @Test
    void malformedConditionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("no operator here"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("== 5"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("x =="));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("   "));
    }

    @Test
    void lessThanOrEqualIsNotMisSplitAsLessThan() {
        Condition c = Condition.parse("count <= 3");
        assertTrue(c.evaluate(Map.of("count", 3)));
        assertFalse(c.evaluate(Map.of("count", 4)));
    }
}
