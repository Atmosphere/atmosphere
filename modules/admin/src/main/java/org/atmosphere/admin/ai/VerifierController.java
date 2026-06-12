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
package org.atmosphere.admin.ai;

import org.atmosphere.verifier.PlanAndVerify;
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.policy.NumericInvariant;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.SmtChecker;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-plane controller backing the Atmosphere Console's
 * <em>Validation</em> tab. Surfaces the Meijer-style plan-and-verify stack so
 * operators can answer, from the console, "what does this deployment's
 * verifier chain refuse, and why?".
 *
 * <p>All three endpoints report runtime-confirmed state only (Correctness
 * Invariant #5, Runtime Truth): the verifier list is the chain
 * {@link PlanAndVerify} actually runs (ServiceLoader-discovered, priority
 * ordered), the SMT solver name is the one {@link SmtChecker#resolve()}
 * resolved at boot (never classpath presence), and {@link #check(String)}
 * runs the real chain over the real planner — no fixture verdicts.</p>
 *
 * <p>{@code summary} and {@code examples} are read-only. {@code check} plans
 * and verifies a goal and, only when the plan passes every verifier, executes
 * it. The Spring Boot starter gates that endpoint through the admin write
 * guard because an application can wire this controller against mutating
 * tools (Correctness Invariant #6).</p>
 */
public final class VerifierController {

    private static final Logger logger = LoggerFactory.getLogger(VerifierController.class);

    private final PlanAndVerify planAndVerify;
    private final VerifierExampleSource exampleSource;

    public VerifierController(PlanAndVerify planAndVerify, VerifierExampleSource exampleSource) {
        this.planAndVerify = planAndVerify;
        this.exampleSource = exampleSource;
    }

    /**
     * Describe the active verifier chain and policy: the ordered verifier
     * names, the resolved SMT solver, and the declarative policy (allowlist,
     * taint rules, numeric invariants). Drives the tab's static panels.
     */
    public Map<String, Object> summary() {
        var out = new LinkedHashMap<String, Object>();

        var verifiers = new ArrayList<Map<String, Object>>();
        for (PlanVerifier v : planAndVerify.verifiers()) {
            var e = new LinkedHashMap<String, Object>();
            e.put("name", v.name());
            e.put("priority", v.priority());
            verifiers.add(e);
        }
        out.put("verifiers", verifiers);
        out.put("smtSolver", SmtChecker.resolve().name());
        out.put("policy", renderPolicy(planAndVerify.policy()));
        out.put("hasExamples", exampleSource != null && !exampleSource.examples().isEmpty());
        return out;
    }

    /**
     * The app-supplied example goals, or an empty list when no
     * {@link VerifierExampleSource} is wired.
     */
    public List<Map<String, Object>> examples() {
        if (exampleSource == null) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        for (VerifierExampleSource.Example ex : exampleSource.examples()) {
            var e = new LinkedHashMap<String, Object>();
            e.put("id", ex.id());
            e.put("label", ex.label());
            e.put("goal", ex.goal());
            e.put("description", ex.description());
            out.add(e);
        }
        return out;
    }

    /**
     * Plan {@code goal}, run every verifier over the resulting AST, and —
     * only when the whole chain passes — execute it. Returns the plan AST,
     * a per-verifier pass/fail breakdown, the merged violations, and (on
     * success) the executed environment.
     */
    public Map<String, Object> check(String goal) {
        var out = new LinkedHashMap<String, Object>();
        out.put("goal", goal);

        Workflow workflow;
        try {
            workflow = planAndVerify.plan(goal);
        } catch (RuntimeException e) {
            // Planning itself failed (e.g. the planner emitted malformed
            // JSON). Surface it as a refusal rather than a 500 so the tab can
            // render it (Correctness Invariant #4, Boundary Safety).
            logger.debug("Planning failed for goal '{}'", goal, e);
            out.put("status", "error");
            out.put("error", e.getMessage());
            return out;
        }

        out.put("plan", renderWorkflow(workflow));

        var policy = planAndVerify.policy();
        var registry = planAndVerify.registry();
        var perVerifier = new ArrayList<Map<String, Object>>();
        var allViolations = new ArrayList<Violation>();
        boolean ok = true;
        for (PlanVerifier v : planAndVerify.verifiers()) {
            VerificationResult r = v.verify(workflow, policy, registry);
            var e = new LinkedHashMap<String, Object>();
            e.put("name", v.name());
            e.put("ok", r.isOk());
            e.put("violations", renderViolations(r.violations()));
            perVerifier.add(e);
            if (!r.isOk()) {
                ok = false;
                allViolations.addAll(r.violations());
            }
        }
        out.put("verifiers", perVerifier);
        out.put("violations", renderViolations(allViolations));

        if (ok) {
            try {
                out.put("env", planAndVerify.run(goal, Map.of()));
                out.put("status", "executed");
            } catch (RuntimeException e) {
                // A verifier passed but execution threw — report honestly
                // rather than claim success (Correctness Invariant #2).
                logger.debug("Execution failed after a clean verify for goal '{}'", goal, e);
                out.put("status", "error");
                out.put("error", e.getMessage());
            }
        } else {
            out.put("status", "refused");
        }
        return out;
    }

    // ── rendering helpers ──────────────────────────────────────────────

    private Map<String, Object> renderPolicy(Policy policy) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", policy.name());
        out.put("controlFlow", policy.controlFlow().name());
        out.put("allowedTools", new ArrayList<>(policy.allowedTools()));
        out.put("taintRuleCount", policy.taintRules().size());
        out.put("automatonCount", policy.automata().size());
        var invariants = new ArrayList<String>();
        for (NumericInvariant inv : policy.numericInvariants()) {
            invariants.add(inv.toolName() + "." + inv.argName()
                    + " " + inv.op() + " " + describeBound(inv.bound()));
        }
        out.put("numericInvariants", invariants);
        return out;
    }

    private String describeBound(NumericInvariant.Bound bound) {
        return switch (bound) {
            case NumericInvariant.LiteralBound lit -> Long.toString(lit.value());
            case NumericInvariant.RefBound ref -> "ref(" + ref.ref() + ")";
        };
    }

    private Map<String, Object> renderWorkflow(Workflow workflow) {
        var out = new LinkedHashMap<String, Object>();
        out.put("goal", workflow.goal());
        var steps = new ArrayList<Map<String, Object>>();
        for (WorkflowStep step : workflow.steps()) {
            var s = new LinkedHashMap<String, Object>();
            s.put("label", step.label());
            if (step.node() instanceof ToolCallNode call) {
                s.put("tool", call.toolName());
                var args = new LinkedHashMap<String, Object>();
                for (var entry : call.arguments().entrySet()) {
                    args.put(entry.getKey(), renderArg(entry.getValue()));
                }
                s.put("arguments", args);
                if (call.hasResultBinding()) {
                    s.put("binding", call.resultBinding());
                }
            }
            steps.add(s);
        }
        out.put("steps", steps);
        return out;
    }

    private Object renderArg(Object value) {
        if (value instanceof SymRef ref) {
            return "@" + ref.ref();
        }
        return value;
    }

    private List<Map<String, Object>> renderViolations(List<Violation> violations) {
        var out = new ArrayList<Map<String, Object>>(violations.size());
        for (Violation v : violations) {
            var m = new LinkedHashMap<String, Object>();
            m.put("category", v.category());
            m.put("message", v.message());
            if (v.astPath() != null) {
                m.put("path", v.astPath());
            }
            out.add(m);
        }
        return out;
    }
}
