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
import org.atmosphere.verifier.policy.NumericInvariant;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.SmtChecker;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.IntegerFormulaManager;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared implementation of an {@link SmtChecker} that proves numeric invariants
 * over the symbolic arguments of a verified plan via the java-smt unified API.
 * The concrete solver is supplied by {@link #solver()}, so the same proof logic
 * drives the pure-JVM SMTInterpol backend ({@link SmtInterpolChecker}) and the
 * native Z3 backend ({@link Z3SmtChecker}) without duplication.
 *
 * <p><strong>What it proves.</strong> For each {@link NumericInvariant} on the
 * policy, this checker finds every matching {@link ToolCallNode} and discharges
 * the proof obligation "for <em>all</em> runtime assignments of the symbolic
 * bindings, the argument satisfies the invariant" by asserting the
 * <em>negation</em> of the invariant in a fresh prover and asking whether it is
 * unsatisfiable:</p>
 *
 * <ul>
 *   <li>UNSAT — no runtime assignment violates the invariant: it is proven and
 *       no violation is emitted.</li>
 *   <li>SAT — a concrete counterexample exists: a {@link Violation} is emitted.</li>
 * </ul>
 *
 * <p><strong>Data-flow identity.</strong> Both {@link SymRef} arguments and
 * {@link NumericInvariant.RefBound}s are keyed by {@code "sym$" + name} when
 * mapped to SMT variables. A plan that passes {@code amount: SymRef("balance")}
 * against {@code transfer.amount <= ref(balance)} maps both sides to the same
 * solver variable, so the negation {@code v > v} is UNSAT — proven. A plan that
 * passes an unrelated symbol maps to distinct variables, the negation is
 * satisfiable, and the plan is flagged.</p>
 *
 * <p><strong>Resource discipline.</strong> {@link #isAvailable()} probes solver
 * load exactly once and caches the result (Correctness Invariant #5: report
 * confirmed runtime state, not classpath presence). {@link #check} creates a
 * fresh {@link SolverContext} per call and closes it (and every
 * {@link ProverEnvironment}) on all paths, so the method is a pure, thread-safe
 * function of its inputs.</p>
 */
public abstract class AbstractJavaSmtChecker implements SmtChecker {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractJavaSmtChecker.class);

    /** Prefix that keys SMT variables by binding name (data-flow identity). */
    private static final String SYM_PREFIX = "sym$";

    /** Category for every violation this checker emits. */
    private static final String CATEGORY = "smt";

    /**
     * Cached result of the one-time availability probe. {@code null} until the
     * first {@link #isAvailable()} call; thereafter the probed boolean.
     */
    private volatile Boolean available;

    /**
     * The java-smt solver this checker drives. Subclasses return a stable
     * constant (e.g. {@code Solvers.SMTINTERPOL} or {@code Solvers.Z3}).
     *
     * @return the solver backend to instantiate
     */
    protected abstract Solvers solver();

    /**
     * Whether the configured {@link #solver()} actually loads in this JVM.
     * Probes by constructing (and immediately closing) one {@link SolverContext};
     * the result is cached so the probe runs at most once. Returns {@code false}
     * (logging at debug) if the solver cannot be created — e.g. a native backend
     * whose libraries are not on {@code java.library.path}. Never reports
     * available on classpath presence alone (Correctness Invariant #5).
     */
    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available != null) {
                return available;
            }
            boolean ok;
            try (SolverContext probe = newContext()) {
                // Touch the formula manager to confirm the solver is wired up,
                // not merely that the context object was allocated.
                probe.getFormulaManager().getIntegerFormulaManager();
                ok = true;
            } catch (Exception | LinkageError e) {
                LOG.debug("Solver {} unavailable; {} disabled",
                        solver(), getClass().getSimpleName(), e);
                ok = false;
            }
            available = ok;
            return ok;
        }
    }

    /**
     * Prove every {@link NumericInvariant} declared by {@code policy} against
     * the matching tool calls in {@code workflow}. Returns an empty-violation
     * result when all invariants are proven; otherwise one violation per
     * unprovable (or non-numeric) invariant occurrence.
     */
    @Override
    public VerificationResult check(Workflow workflow, Policy policy) {
        List<NumericInvariant> invariants = policy.numericInvariants();
        if (invariants.isEmpty()) {
            return VerificationResult.ok();
        }
        List<Violation> violations = new ArrayList<>();
        try (SolverContext ctx = newContext()) {
            IntegerFormulaManager imgr =
                    ctx.getFormulaManager().getIntegerFormulaManager();
            BooleanFormulaManager bmgr =
                    ctx.getFormulaManager().getBooleanFormulaManager();
            for (NumericInvariant inv : invariants) {
                checkInvariant(ctx, imgr, bmgr, workflow, inv, violations);
            }
        } catch (Exception e) {
            // A solver failure must not silently pass a plan: fail closed by
            // surfacing it as a violation (Correctness Invariant #6).
            LOG.debug("SMT solver error while checking numeric invariants", e);
            violations.add(new Violation(CATEGORY,
                    "SMT solver error: " + e.getMessage(), null));
        }
        return VerificationResult.of(violations);
    }

    /**
     * Discharge one invariant against every matching tool call, appending any
     * violations found.
     */
    private void checkInvariant(SolverContext ctx,
                                IntegerFormulaManager imgr,
                                BooleanFormulaManager bmgr,
                                Workflow workflow,
                                NumericInvariant inv,
                                List<Violation> violations) {
        String astPath = inv.toolName() + "." + inv.argName();
        for (var step : workflow.steps()) {
            if (!(step.node() instanceof ToolCallNode node)) {
                continue;
            }
            if (!node.toolName().equals(inv.toolName())) {
                continue;
            }
            Map<String, Object> args = node.arguments();
            if (!args.containsKey(inv.argName())) {
                // Argument absent — invariant vacuously satisfied for this call.
                continue;
            }
            IntegerFormula argFormula = toIntegerFormula(imgr, args.get(inv.argName()));
            if (argFormula == null) {
                violations.add(new Violation(CATEGORY,
                        "non-numeric argument for numeric invariant " + astPath,
                        astPath));
                continue;
            }
            IntegerFormula boundFormula = boundFormula(imgr, inv.bound());
            BooleanFormula invariantHolds =
                    comparison(imgr, inv.op(), argFormula, boundFormula);
            if (!prove(ctx, bmgr, invariantHolds)) {
                violations.add(new Violation(CATEGORY,
                        astPath + " " + inv.op() + " " + describe(inv.bound())
                                + " is not provable for all runtime values",
                        astPath));
            }
        }
    }

    /**
     * Map an argument value to an {@link IntegerFormula}: a symbolic variable
     * for a {@link SymRef}, a numeric constant for a {@code Number} or numeric
     * {@code String}, or {@code null} for any non-numeric literal.
     */
    private IntegerFormula toIntegerFormula(IntegerFormulaManager imgr, Object value) {
        if (value instanceof SymRef ref) {
            return imgr.makeVariable(SYM_PREFIX + ref.ref());
        }
        if (value instanceof Number number) {
            return imgr.makeNumber(number.longValue());
        }
        if (value instanceof String s) {
            try {
                return imgr.makeNumber(Long.parseLong(s.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Map a {@link NumericInvariant.Bound} to an {@link IntegerFormula}: a
     * constant for a literal, a symbolic variable for a reference.
     */
    private IntegerFormula boundFormula(IntegerFormulaManager imgr,
                                        NumericInvariant.Bound bound) {
        return switch (bound) {
            case NumericInvariant.LiteralBound lit -> imgr.makeNumber(lit.value());
            case NumericInvariant.RefBound ref -> imgr.makeVariable(SYM_PREFIX + ref.ref());
        };
    }

    /**
     * Build the boolean formula asserting that {@code arg op bound} holds.
     */
    private BooleanFormula comparison(IntegerFormulaManager imgr,
                                      NumericInvariant.Op op,
                                      IntegerFormula arg,
                                      IntegerFormula bound) {
        return switch (op) {
            case LE -> imgr.lessOrEquals(arg, bound);
            case LT -> imgr.lessThan(arg, bound);
            case GE -> imgr.greaterOrEquals(arg, bound);
            case GT -> imgr.greaterThan(arg, bound);
            case EQ -> imgr.equal(arg, bound);
        };
    }

    /**
     * Returns {@code true} when {@code invariantHolds} is valid for all runtime
     * assignments — i.e. its negation is unsatisfiable. The prover is closed on
     * every path.
     */
    private boolean prove(SolverContext ctx,
                          BooleanFormulaManager bmgr,
                          BooleanFormula invariantHolds) {
        try (ProverEnvironment prover = ctx.newProverEnvironment()) {
            prover.addConstraint(bmgr.not(invariantHolds));
            return prover.isUnsat();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SMT proof interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("SMT proof failed", e);
        }
    }

    /**
     * Human-readable rendering of a bound for violation messages.
     */
    private String describe(NumericInvariant.Bound bound) {
        return switch (bound) {
            case NumericInvariant.LiteralBound lit -> Long.toString(lit.value());
            case NumericInvariant.RefBound ref -> "ref(" + ref.ref() + ")";
        };
    }

    /**
     * Construct a fresh {@link SolverContext} for the configured {@link #solver()}.
     * Callers own the returned context and must close it.
     */
    private SolverContext newContext() throws Exception {
        Configuration config = Configuration.defaultConfiguration();
        LogManager logger = BasicLogManager.create(config);
        ShutdownManager shutdown = ShutdownManager.create();
        return SolverContextFactory.createSolverContext(
                config, logger, shutdown.getNotifier(), solver());
    }
}
