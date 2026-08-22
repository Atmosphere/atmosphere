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
package org.atmosphere.admin.workflow;

import org.atmosphere.admin.ControlAuditLog;
import org.atmosphere.admin.ControlAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Authoring controller for workflows defined through the admin control
 * plane. Sibling to {@code FlowController} (read-only journal viewer) and
 * the governance / framework controllers — the read-side of the
 * coordination story is observability, the write-side is workflow
 * authoring.
 *
 * <p>All mutating operations route through {@link ControlAuthorizer} and
 * emit an entry to {@link ControlAuditLog} so an operator can answer
 * "who changed which workflow, when, and why" from the audit surface
 * alone (Correctness Invariant #6 — Security: every mutating endpoint
 * requires explicit authorization, and the audit log records the
 * grant).</p>
 *
 * <p>The HTTP layer in the Spring Boot / Quarkus admin extensions maps
 * controller methods to endpoints:</p>
 * <ul>
 *   <li>{@code GET    /api/admin/workflow}        → {@link #list()}</li>
 *   <li>{@code GET    /api/admin/workflow/{id}}   → {@link #get(String)}</li>
 *   <li>{@code POST   /api/admin/workflow}        → {@link #save(WorkflowManifest, String)}</li>
 *   <li>{@code DELETE /api/admin/workflow/{id}}   → {@link #delete(String, String)}</li>
 * </ul>
 */
public final class WorkflowController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowStore store;
    private final ControlAuthorizer authorizer;
    private final ControlAuditLog auditLog;
    private final WorkflowRunner runner;

    public WorkflowController(WorkflowStore store, ControlAuthorizer authorizer,
                              ControlAuditLog auditLog) {
        this(store, authorizer, auditLog, null);
    }

    /**
     * @param runner the execution engine behind {@link #run}; when
     *               {@code null} the run surface reports itself unavailable
     *               loudly instead of pretending manifests execute
     */
    public WorkflowController(WorkflowStore store, ControlAuthorizer authorizer,
                              ControlAuditLog auditLog, WorkflowRunner runner) {
        this.store = store != null ? store : new InMemoryWorkflowStore();
        this.authorizer = authorizer != null ? authorizer : ControlAuthorizer.DENY_ALL;
        this.auditLog = auditLog;
        this.runner = runner;
    }

    /** The execution engine, or {@code null} when this deployment wired none. */
    public WorkflowRunner runner() {
        return runner;
    }

    /** Read-only list. No authorization check — the read surface mirrors {@code FlowController}. */
    public List<WorkflowManifest> list() {
        return store.list();
    }

    /** Read-only single fetch. */
    public Optional<WorkflowManifest> get(String id) {
        return store.findById(id);
    }

    /**
     * Create or update a workflow. The caller-supplied
     * {@link WorkflowManifest#version()} must be exactly
     * {@code existing.version + 1} for updates (optimistic concurrency).
     * For new workflows, the version must be {@code 1}.
     *
     * @throws SecurityException if the principal lacks
     *         {@code workflow.write} authorization
     */
    public WorkflowManifest save(WorkflowManifest manifest, String principal) {
        if (!authorizer.authorize("workflow.write", manifest.id(), principal)) {
            logger.warn("workflow.write denied: principal={} workflow={}", principal, manifest.id());
            throw new SecurityException(
                    "principal " + principal + " is not authorized to write workflow "
                            + manifest.id());
        }
        var saved = store.save(manifest);
        record("workflow.write", saved.id(), principal,
                "workflow saved (name=" + saved.name() + " v" + saved.version() + ")");
        return saved;
    }

    /**
     * Delete a workflow. Idempotent — deleting a non-existent id is not an
     * error, but the audit log still records the attempt for accountability.
     *
     * @throws SecurityException if the principal lacks
     *         {@code workflow.delete} authorization
     */
    public void delete(String id, String principal) {
        if (!authorizer.authorize("workflow.delete", id, principal)) {
            logger.warn("workflow.delete denied: principal={} workflow={}", principal, id);
            throw new SecurityException(
                    "principal " + principal + " is not authorized to delete workflow " + id);
        }
        store.delete(id);
        record("workflow.delete", id, principal, "workflow deleted");
    }

    /**
     * Execute a saved workflow through the wired {@link WorkflowRunner}.
     * Blocks the calling (virtual) thread until the run reaches its
     * terminal state — including any approval gates the manifest parks on.
     *
     * @param id              the workflow to run
     * @param coordinatorName the coordinator fleet to dispatch against; when
     *                        {@code null} the runner's single registered
     *                        fleet is used
     * @param input           run input; {@code input.get("input")} seeds the
     *                        root nodes
     * @return the completed run record
     * @throws SecurityException        if the principal lacks {@code workflow.run}
     * @throws java.util.NoSuchElementException if the workflow id is unknown
     * @throws IllegalStateException    if no runner is wired
     * @throws IllegalArgumentException if the coordinator cannot be resolved
     */
    public WorkflowRun run(String id, String coordinatorName,
                           java.util.Map<String, Object> input, String principal) {
        if (!authorizer.authorize("workflow.run", id, principal)) {
            logger.warn("workflow.run denied: principal={} workflow={}", principal, id);
            throw new SecurityException(
                    "principal " + principal + " is not authorized to run workflow " + id);
        }
        if (runner == null) {
            throw new IllegalStateException("no WorkflowRunner wired — workflow execution "
                    + "is unavailable in this deployment");
        }
        var manifest = store.findById(id).orElseThrow(() ->
                new java.util.NoSuchElementException("no workflow: " + id));
        var coordinator = coordinatorName != null && !coordinatorName.isBlank()
                ? coordinatorName
                : runner.defaultCoordinator();
        if (coordinator == null) {
            throw new IllegalArgumentException(
                    "coordinator must be specified — zero or multiple fleets are registered");
        }
        var run = runner.run(manifest, coordinator, input);
        record("workflow.run", id, principal,
                "workflow executed (run=" + run.runId() + " coordinator=" + coordinator
                        + " status=" + run.status() + ")");
        return run;
    }

    /**
     * Resolve an approval gate a running workflow is parked on.
     *
     * @throws SecurityException if the principal lacks {@code workflow.approve}
     * @throws IllegalStateException if no runner is wired
     */
    public boolean resolveApproval(String approvalId, boolean approve, String principal) {
        if (!authorizer.authorize("workflow.approve", approvalId, principal)) {
            logger.warn("workflow.approve denied: principal={} approval={}", principal, approvalId);
            throw new SecurityException(
                    "principal " + principal + " is not authorized to resolve approval " + approvalId);
        }
        if (runner == null) {
            throw new IllegalStateException("no WorkflowRunner wired — workflow execution "
                    + "is unavailable in this deployment");
        }
        var resolved = runner.resolveApproval(approvalId, approve);
        record("workflow.approve", approvalId, principal,
                (approve ? "approved" : "denied") + " (resolved=" + resolved + ")");
        return resolved;
    }

    private void record(String action, String target, String principal, String detail) {
        if (auditLog == null) {
            return;
        }
        try {
            auditLog.record(principal, action, target, true, detail);
        } catch (RuntimeException re) {
            // Audit-log failure must not break the mutating call but must be
            // visible — the operator may have a broken sink.
            logger.warn("audit-log record failed for {} on {}: {}", action, target, re.getMessage());
        }
    }
}
