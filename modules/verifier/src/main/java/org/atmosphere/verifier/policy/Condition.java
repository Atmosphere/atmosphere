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

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * A small, total guard expression of the form {@code <var> <op> <operand>},
 * shared by two consumers:
 *
 * <ul>
 *   <li>{@link AutomatonTransition#condition()} — a guard the
 *       {@link org.atmosphere.verifier.checks.AutomatonVerifier} evaluates
 *       <em>statically</em> against a tool call's arguments. When operand
 *       values are not literally known (e.g. a symbolic reference resolved
 *       only at run time) the guard evaluates to {@link Tristate#UNKNOWN}
 *       and the verifier soundly explores both the taken and not-taken
 *       successor states.</li>
 *   <li>{@code org.atmosphere.verifier.ast.ConditionalNode#predicate()} —
 *       the branch selector the
 *       {@link org.atmosphere.verifier.execute.WorkflowExecutor} evaluates
 *       <em>at run time</em> against the resolved environment to pick the
 *       {@code then} or {@code else} arm.</li>
 * </ul>
 *
 * <h2>Grammar</h2>
 * <pre>{@code
 * condition := operand OP operand
 * OP        := "==" | "!=" | "<=" | ">=" | "<" | ">"
 * }</pre>
 *
 * The left side is always a variable name (an optional leading {@code @} is
 * stripped, so {@code "@amount"} and {@code "amount"} are equivalent). The
 * right side is a {@code @}-prefixed variable reference, a number, a boolean
 * ({@code true}/{@code false}), or a (optionally quoted) string literal. A
 * bare unquoted word on the right is a string literal, so
 * {@code "status == approved"} compares the {@code status} variable against
 * the string {@code "approved"}.
 *
 * <p>Equality ({@code ==}, {@code !=}) compares numerically when both sides
 * are numbers and by string value otherwise. Ordering comparisons
 * ({@code <}, {@code <=}, {@code >}, {@code >=}) require both sides to be
 * numeric; against non-numeric operands they are {@link Tristate#UNKNOWN}
 * statically and throw at run time.</p>
 *
 * <p>Instances are immutable and safe to cache; parsing is a pure function
 * of the source string.</p>
 */
public final class Condition {

    /** Three-valued result of a static (verification-time) evaluation. */
    public enum Tristate { TRUE, FALSE, UNKNOWN }

    private enum Op {
        EQ("=="), NE("!="), LE("<="), GE(">="), LT("<"), GT(">");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    // Longest symbols first so "<=" is never mis-split as "<".
    private static final Op[] BY_LENGTH = {Op.EQ, Op.NE, Op.LE, Op.GE, Op.LT, Op.GT};

    private final String source;
    private final String leftVar;
    private final Op op;
    private final Operand right;

    private Condition(String source, String leftVar, Op op, Operand right) {
        this.source = source;
        this.leftVar = leftVar;
        this.op = op;
        this.right = right;
    }

    /**
     * Parse a guard expression.
     *
     * @throws IllegalArgumentException if {@code source} is blank or has no
     *         recognised operator or an empty operand.
     */
    public static Condition parse(String source) {
        Objects.requireNonNull(source, "source");
        String s = source.strip();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("condition must not be blank");
        }
        for (Op candidate : BY_LENGTH) {
            int idx = s.indexOf(candidate.symbol);
            if (idx <= 0) {
                // idx == 0 means no left operand; idx < 0 means absent.
                continue;
            }
            String left = s.substring(0, idx).strip();
            String rightToken = s.substring(idx + candidate.symbol.length()).strip();
            if (left.isEmpty() || rightToken.isEmpty()) {
                throw new IllegalArgumentException(
                        "condition '" + source + "' has an empty operand");
            }
            String leftVar = left.startsWith("@") ? left.substring(1) : left;
            if (leftVar.isEmpty()) {
                throw new IllegalArgumentException(
                        "condition '" + source + "' has an empty left variable");
            }
            return new Condition(source, leftVar, candidate, Operand.parse(rightToken));
        }
        throw new IllegalArgumentException(
                "condition '" + source + "' has no comparison operator "
                        + "(expected one of ==, !=, <=, >=, <, >)");
    }

    /** Names this condition reads — used to scope-check predicates. */
    public Set<String> referencedNames() {
        var names = new LinkedHashSet<String>();
        names.add(leftVar);
        if (right instanceof Operand.VarRef ref) {
            names.add(ref.name());
        }
        return names;
    }

    /**
     * Evaluate against a resolved environment. Every referenced variable
     * must be present.
     *
     * @throws IllegalArgumentException when a referenced variable is absent,
     *         or an ordering comparison is applied to a non-numeric value.
     */
    public boolean evaluate(java.util.Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        if (!values.containsKey(leftVar)) {
            throw new IllegalArgumentException(
                    "condition '" + source + "' references unbound variable '"
                            + leftVar + "'");
        }
        Object leftVal = values.get(leftVar);
        Object rightVal = right.resolveRuntime(values, source);
        return compareRuntime(leftVal, rightVal);
    }

    /**
     * Evaluate statically: {@link Tristate#TRUE}/{@link Tristate#FALSE} when
     * both operands are literally known, {@link Tristate#UNKNOWN} otherwise
     * (e.g. an operand is a symbolic reference resolved only at run time).
     */
    public Tristate evaluateStatic(java.util.Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        Object leftVal = literalOrNull(values.get(leftVar), values.containsKey(leftVar));
        if (leftVal == null) {
            return Tristate.UNKNOWN;
        }
        Object rightVal = right.resolveStatic(values);
        if (rightVal == null) {
            return Tristate.UNKNOWN;
        }
        Boolean result = compareStatic(leftVal, rightVal);
        if (result == null) {
            return Tristate.UNKNOWN;
        }
        return result ? Tristate.TRUE : Tristate.FALSE;
    }

    @Override
    public String toString() {
        return source;
    }

    // ── comparison ──────────────────────────────────────────────────────

    private boolean compareRuntime(Object left, Object right) {
        return switch (op) {
            case EQ -> valuesEqual(left, right);
            case NE -> !valuesEqual(left, right);
            case LT, LE, GT, GE -> {
                OptionalDouble l = toDouble(left);
                OptionalDouble r = toDouble(right);
                if (l.isEmpty() || r.isEmpty()) {
                    throw new IllegalArgumentException(
                            "condition '" + source + "' applies an ordering "
                                    + "comparison to a non-numeric value");
                }
                yield compareDoubles(l.getAsDouble(), r.getAsDouble());
            }
        };
    }

    /** Returns null when the comparison cannot be decided (non-numeric ordering). */
    private Boolean compareStatic(Object left, Object right) {
        return switch (op) {
            case EQ -> valuesEqual(left, right);
            case NE -> !valuesEqual(left, right);
            case LT, LE, GT, GE -> {
                OptionalDouble l = toDouble(left);
                OptionalDouble r = toDouble(right);
                if (l.isEmpty() || r.isEmpty()) {
                    yield null;
                }
                yield compareDoubles(l.getAsDouble(), r.getAsDouble());
            }
        };
    }

    private boolean compareDoubles(double l, double r) {
        return switch (op) {
            case LT -> l < r;
            case LE -> l <= r;
            case GT -> l > r;
            case GE -> l >= r;
            default -> throw new IllegalStateException("not an ordering op: " + op);
        };
    }

    private static boolean valuesEqual(Object left, Object right) {
        OptionalDouble l = toDouble(left);
        OptionalDouble r = toDouble(right);
        if (l.isPresent() && r.isPresent()) {
            return l.getAsDouble() == r.getAsDouble();
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static OptionalDouble toDouble(Object value) {
        if (value instanceof Number n) {
            return OptionalDouble.of(n.doubleValue());
        }
        if (value instanceof String s) {
            try {
                return OptionalDouble.of(Double.parseDouble(s.strip()));
            } catch (NumberFormatException ignored) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * A value is "literally known" for static evaluation only when it is a
     * String, Number, or Boolean. Anything else (a symbolic reference, a
     * nested structure, or an absent key) is statically unknown.
     */
    private static Object literalOrNull(Object value, boolean present) {
        if (!present) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return null;
    }

    // ── operands ────────────────────────────────────────────────────────

    private sealed interface Operand {

        /** Resolve for runtime evaluation; throws on an unbound reference. */
        Object resolveRuntime(java.util.Map<String, ?> values, String source);

        /** Resolve for static evaluation; null = not literally known. */
        Object resolveStatic(java.util.Map<String, ?> values);

        static Operand parse(String token) {
            if (token.startsWith("@") && token.length() > 1) {
                return new VarRef(token.substring(1));
            }
            if ((token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2)
                    || (token.startsWith("'") && token.endsWith("'") && token.length() >= 2)) {
                return new Literal(token.substring(1, token.length() - 1));
            }
            if (token.equalsIgnoreCase("true")) {
                return new Literal(Boolean.TRUE);
            }
            if (token.equalsIgnoreCase("false")) {
                return new Literal(Boolean.FALSE);
            }
            try {
                return new Literal(Long.valueOf(Long.parseLong(token)));
            } catch (NumberFormatException notLong) {
                // fall through to double, then bare string
            }
            try {
                return new Literal(Double.valueOf(Double.parseDouble(token)));
            } catch (NumberFormatException notDouble) {
                return new Literal(token);
            }
        }

        record Literal(Object value) implements Operand {
            @Override
            public Object resolveRuntime(java.util.Map<String, ?> values, String source) {
                return value;
            }

            @Override
            public Object resolveStatic(java.util.Map<String, ?> values) {
                return value;
            }
        }

        record VarRef(String name) implements Operand {
            @Override
            public Object resolveRuntime(java.util.Map<String, ?> values, String source) {
                if (!values.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "condition '" + source + "' references unbound variable '"
                                    + name + "'");
                }
                return values.get(name);
            }

            @Override
            public Object resolveStatic(java.util.Map<String, ?> values) {
                return literalOrNull(values.get(name), values.containsKey(name));
            }
        }
    }
}
