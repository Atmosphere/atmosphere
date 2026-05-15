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
package org.atmosphere.admin.evals;

import org.atmosphere.admin.ControlAuditLog;
import org.atmosphere.admin.ControlAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eval dashboard controller. Sibling to {@code FlowController} and
 * {@code WorkflowController} — surfaces evaluation runs (LLM-as-judge,
 * golden-trace comparisons) submitted by CI or by manual operator action so
 * an admin can see pass/fail trends without leaving the control plane.
 *
 * <p>Wire-level shape:</p>
 * <ul>
 *   <li>{@code GET    /api/admin/evals/runs}                — most recent runs across all baselines</li>
 *   <li>{@code GET    /api/admin/evals/baselines}           — current pass-rate per baseline</li>
 *   <li>{@code GET    /api/admin/evals/runs/{id}}           — single run drill-down</li>
 *   <li>{@code POST   /api/admin/evals/runs}                — record a new run (CI submits here)</li>
 *   <li>{@code DELETE /api/admin/evals/runs/{id}}           — purge a run</li>
 * </ul>
 *
 * <p>POST and DELETE route through {@link ControlAuthorizer} and emit an
 * audit log entry, matching the pattern in {@code WorkflowController}.</p>
 */
public final class EvalController {

    private static final Logger logger = LoggerFactory.getLogger(EvalController.class);

    private final EvalRunStore store;
    private final ControlAuthorizer authorizer;
    private final ControlAuditLog auditLog;

    public EvalController(EvalRunStore store, ControlAuthorizer authorizer,
                          ControlAuditLog auditLog) {
        this.store = store != null ? store : new InMemoryEvalRunStore();
        this.authorizer = authorizer != null ? authorizer : ControlAuthorizer.DENY_ALL;
        this.auditLog = auditLog;
    }

    /** Read-only list of all recorded runs, most recent first. */
    public List<EvalRun> listRuns() {
        return store.list();
    }

    /** Read-only list of runs scoped to a baseline. */
    public List<EvalRun> listRuns(String baseline) {
        return store.listForBaseline(baseline);
    }

    /** Single run by id. */
    public Optional<EvalRun> getRun(String id) {
        return store.findById(id);
    }

    /**
     * Pass-rate per baseline: how many runs we have for each baseline and
     * how many of them passed. The map is sorted by baseline name so the UI
     * gets a stable order across refreshes.
     */
    public List<Map<String, Object>> baselineSummary() {
        var byBaseline = new HashMap<String, BaselineStats>();
        for (var run : store.list()) {
            var stats = byBaseline.computeIfAbsent(run.baseline(), b -> new BaselineStats());
            stats.total++;
            if (run.passed()) {
                stats.passed++;
            }
            if (stats.lastRunAt == null || run.timestamp().isAfter(stats.lastRunAt)) {
                stats.lastRunAt = run.timestamp();
                stats.lastVerdict = run.passed();
            }
        }
        return byBaseline.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    var s = entry.getValue();
                    var row = new LinkedHashMap<String, Object>();
                    row.put("baseline", entry.getKey());
                    row.put("total", s.total);
                    row.put("passed", s.passed);
                    row.put("passRate", s.total == 0 ? 0.0 : ((double) s.passed) / s.total);
                    row.put("lastRunAt", s.lastRunAt != null ? s.lastRunAt.toString() : null);
                    row.put("lastVerdict", s.lastVerdict);
                    return (Map<String, Object>) row;
                })
                .toList();
    }

    /** Record a new eval run. Mutating — requires {@code evals.write} grant. */
    public EvalRun record(EvalRun run, String principal) {
        if (!authorizer.authorize("evals.write", run.baseline(), principal)) {
            logger.warn("evals.write denied: principal={} baseline={}", principal, run.baseline());
            throw new SecurityException(
                    "principal " + principal + " is not authorized to record eval run for baseline "
                            + run.baseline());
        }
        var saved = store.save(run);
        audit("evals.write", saved.id(), principal,
                "eval recorded (baseline=" + saved.baseline()
                        + " passed=" + saved.passed()
                        + " judge=" + saved.judgeModel() + ")");
        return saved;
    }

    /** Delete an eval run. Mutating — requires {@code evals.delete} grant. */
    public void delete(String id, String principal) {
        if (!authorizer.authorize("evals.delete", id, principal)) {
            logger.warn("evals.delete denied: principal={} run={}", principal, id);
            throw new SecurityException(
                    "principal " + principal + " is not authorized to delete eval run " + id);
        }
        store.delete(id);
        audit("evals.delete", id, principal, "eval deleted");
    }

    private void audit(String action, String target, String principal, String detail) {
        if (auditLog == null) {
            return;
        }
        try {
            auditLog.record(principal, action, target, true, detail);
        } catch (RuntimeException re) {
            logger.warn("audit-log record failed for {} on {}: {}", action, target, re.getMessage());
        }
    }

    private static final class BaselineStats {
        int total;
        int passed;
        java.time.Instant lastRunAt;
        Boolean lastVerdict;
    }
}
