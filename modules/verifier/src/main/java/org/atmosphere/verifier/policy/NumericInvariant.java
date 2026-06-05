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
package org.atmosphere.verifier.policy;

import java.util.Objects;

/**
 * A numeric invariant the SMT checker proves over a tool call's symbolic
 * arguments. Unlike the structural verifiers (allowlist, taint, capability,
 * automaton), a numeric invariant constrains the <em>value</em> flowing into
 * a tool argument — and because that value may be a symbolic, unbounded
 * runtime binding, the proof obligation is genuinely an SMT problem, not a
 * structural check.
 *
 * <p><strong>Worked example — the transfer/balance invariant.</strong> A
 * policy declares that the {@code transfer} tool's {@code amount} argument
 * must never exceed the runtime {@code balance} binding:</p>
 *
 * <pre>{@code
 * new NumericInvariant("transfer", "amount",
 *         NumericInvariant.Op.LE,
 *         new NumericInvariant.RefBound("balance"));
 * }</pre>
 *
 * <p>A plan that passes {@code amount: SymRef("balance")} — i.e. binds the
 * very value that was read as the balance — is provably safe: the SMT solver
 * proves {@code amount <= balance} for <em>all</em> runtime values (the
 * negation is UNSAT). A plan that passes some other symbol, e.g.
 * {@code amount: SymRef("userInput")}, is flagged: there exists a runtime
 * assignment under which {@code userInput > balance} (the negation is SAT,
 * and the SAT model is a concrete counterexample).</p>
 *
 * <p>The {@link Bound} alternatives capture the two flavours of the right-hand
 * side: a fixed {@link LiteralBound} (e.g. {@code amount <= 1000}) or a
 * symbolic {@link RefBound} that names another runtime binding (e.g.
 * {@code amount <= ref(balance)}). The SMT checker keys both {@code SymRef}
 * arguments and {@code RefBound}s by their binding name, so the same name on
 * both sides denotes the same SMT variable — that identity is what models the
 * data-flow link between the read value and the argument.</p>
 *
 * @param toolName name of the tool the invariant constrains; non-blank. The
 *                 invariant applies to every {@code ToolCallNode} whose tool
 *                 name matches.
 * @param argName  name of the argument whose value is constrained; non-blank.
 *                 If a matching tool call omits this argument, the invariant
 *                 is vacuously satisfied for that call.
 * @param op       the comparison operator; non-null.
 * @param bound    the right-hand side of the comparison; non-null.
 */
public record NumericInvariant(String toolName, String argName, Op op, Bound bound) {

    public NumericInvariant {
        Objects.requireNonNull(toolName, "toolName");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(argName, "argName");
        if (argName.isBlank()) {
            throw new IllegalArgumentException("argName must not be blank");
        }
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(bound, "bound");
    }

    /**
     * Comparison operator relating the constrained argument (left-hand side)
     * to the {@link Bound} (right-hand side).
     */
    public enum Op {
        /** Argument must be less than or equal to the bound. */
        LE,
        /** Argument must be strictly less than the bound. */
        LT,
        /** Argument must be greater than or equal to the bound. */
        GE,
        /** Argument must be strictly greater than the bound. */
        GT,
        /** Argument must equal the bound. */
        EQ
    }

    /**
     * Right-hand side of a {@link NumericInvariant} comparison. Either a fixed
     * integer ({@link LiteralBound}) or a reference to another runtime binding
     * ({@link RefBound}).
     */
    public sealed interface Bound permits LiteralBound, RefBound {
    }

    /**
     * A fixed integer bound, e.g. {@code amount <= 1000}.
     *
     * @param value the literal integer bound.
     */
    public record LiteralBound(long value) implements Bound {
    }

    /**
     * A symbolic bound that names another runtime binding, e.g.
     * {@code amount <= ref(balance)}. The {@code ref} string must match the
     * {@link org.atmosphere.verifier.ast.SymRef#ref()} of the binding it
     * refers to so the SMT checker maps both to the same solver variable.
     *
     * @param ref the binding name; non-blank.
     */
    public record RefBound(String ref) implements Bound {
        public RefBound {
            Objects.requireNonNull(ref, "ref");
            if (ref.isBlank()) {
                throw new IllegalArgumentException("ref must not be blank");
            }
        }
    }
}
