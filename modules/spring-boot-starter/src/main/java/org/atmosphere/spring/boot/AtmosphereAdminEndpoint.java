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
package org.atmosphere.spring.boot;

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.a2a.TaskController;
import org.atmosphere.admin.ai.AiRuntimeController;
import org.atmosphere.admin.ai.GovernanceController;
import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.admin.flow.FlowController;
import org.atmosphere.admin.mcp.McpController;
import org.atmosphere.admin.metrics.MetricsController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller exposing the Atmosphere Admin control plane read operations.
 * All endpoints are under {@code /api/admin/}.
 *
 * @since 4.0
 */
@AutoConfiguration
@RestController
@RequestMapping("/api/admin")
@ConditionalOnBean(AtmosphereAdmin.class)
public class AtmosphereAdminEndpoint {

    private final AtmosphereAdmin admin;
    private final org.springframework.core.env.Environment env;

    public AtmosphereAdminEndpoint(
            AtmosphereAdmin admin,
            org.springframework.core.env.Environment env) {
        this.admin = admin;
        this.env = env;
    }

    /**
     * Consulted on every mutating call — not cached — so an operator
     * can flip {@code atmosphere.admin.http-write-enabled=false} at
     * runtime (Spring's {@code @RefreshScope} or a live
     * {@code ConfigFileApplicationListener}) and every subsequent
     * write rejects without a restart. Resolved fresh per request; the
     * cost is one environment lookup per guardWrite call — negligible
     * next to the servlet dispatch.
     */
    boolean writeEnabled() {
        return Boolean.parseBoolean(
                env.getProperty("atmosphere.admin.http-write-enabled", "false"));
    }

    /**
     * Authorization gate for every mutating admin REST endpoint. Three
     * layers, evaluated in order:
     * <ol>
     *   <li><strong>Feature flag</strong> —
     *       {@code atmosphere.admin.http-write-enabled} must be {@code true}.
     *       Returns {@code 403} when disabled.</li>
     *   <li><strong>Authenticated principal</strong> — the servlet
     *       {@code HttpServletRequest.getUserPrincipal()} must be non-null
     *       and non-blank. Returns {@code 401} when anonymous.</li>
     *   <li><strong>{@link org.atmosphere.admin.ControlAuthorizer}</strong>
     *       installed on {@link AtmosphereAdmin} — an operator may plug in a
     *       role/scope/tenant check. Returns {@code 403} on deny.</li>
     * </ol>
     *
     * <p>Every decision (allowed or denied) is recorded in the control audit
     * log so operators see the complete picture.</p>
     *
     * <p>Default deny per Correctness Invariant #6. The earlier
     * feature-flag-only gate let any anonymous caller mutate state once the
     * flag was flipped for machine access — the principal check closes that
     * hole.</p>
     */
    private ResponseEntity<Map<String, Object>> guardWrite(
            HttpServletRequest request, String action, String target) {
        if (!writeEnabled()) {
            admin.auditLog().record(null, action + ".denied.flag", target, false,
                    "atmosphere.admin.http-write-enabled=false");
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Admin write operations disabled",
                    "hint", "Set atmosphere.admin.http-write-enabled=true to enable"));
        }
        // Principal resolution order mirrors AiEndpointHandler:
        //   1. servlet getUserPrincipal() (Spring Security, Jakarta Security)
        //   2. Atmosphere's own AuthInterceptor attribute (token-validated Principal)
        //   3. ai.userId request attribute (framework-set fallback)
        String principalName = null;
        if (request != null) {
            var principal = request.getUserPrincipal();
            if (principal != null && principal.getName() != null
                    && !principal.getName().isBlank()) {
                principalName = principal.getName();
            } else if (request.getAttribute("org.atmosphere.auth.principal")
                    instanceof java.security.Principal attrPrincipal
                    && attrPrincipal.getName() != null
                    && !attrPrincipal.getName().isBlank()) {
                principalName = attrPrincipal.getName();
            } else if (request.getAttribute("ai.userId") instanceof String s
                    && !s.isBlank()) {
                principalName = s;
            }
        }
        if (principalName == null) {
            admin.auditLog().record(null, action + ".denied.anonymous", target, false, null);
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Authentication required for admin write operations"));
        }
        var authorizer = admin.authorizer();
        if (!authorizer.authorize(action, target, principalName)) {
            admin.auditLog().record(principalName, action + ".denied.authz", target, false, null);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Forbidden"));
        }
        return null;
    }

    /**
     * Resolve the authenticated caller's name using the same order as
     * {@code guardWrite}. Must only be called on a request that already
     * passed guardWrite — so principalName is guaranteed non-null.
     */
    private static String resolvePrincipalName(HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null
                && !principal.getName().isBlank()) {
            return principal.getName();
        }
        if (request.getAttribute("org.atmosphere.auth.principal")
                instanceof java.security.Principal attrPrincipal
                && attrPrincipal.getName() != null) {
            return attrPrincipal.getName();
        }
        if (request.getAttribute("ai.userId") instanceof String s) {
            return s;
        }
        return "unknown";
    }

    // ── System Overview ──

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return admin.overview();
    }

    // ── Framework ──

    @GetMapping("/broadcasters")
    public List<Map<String, Object>> listBroadcasters() {
        return admin.framework().listBroadcasters();
    }

    @GetMapping("/broadcasters/detail")
    public ResponseEntity<Map<String, Object>> getBroadcaster(@RequestParam("id") String id) {
        return admin.framework().getBroadcaster(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/resources")
    public List<Map<String, Object>> listResources() {
        return admin.framework().listResources();
    }

    @GetMapping("/handlers")
    public List<Map<String, Object>> listHandlers() {
        return admin.framework().listHandlers();
    }

    @GetMapping("/interceptors")
    public List<Map<String, Object>> listInterceptors() {
        return admin.framework().listInterceptors();
    }

    // ── Framework Write Operations ──

    @PostMapping("/broadcasters/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        var id = body.get("broadcasterId");
        var message = body.get("message");
        var denied = guardWrite(request, "broadcast", id);
        if (denied != null) return denied;
        if (id == null || message == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "missing 'broadcasterId' or 'message' field"));
        }
        var principalName = resolvePrincipalName(request);
        var success = admin.framework().broadcast(id, message);
        admin.auditLog().record(principalName, "broadcast", id, success, message);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "broadcast sent"));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/broadcasters/unicast")
    public ResponseEntity<Map<String, Object>> unicast(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        var id = body.get("broadcasterId");
        var uuid = body.get("uuid");
        var message = body.get("message");
        var target = id != null && uuid != null ? id + "/" + uuid : id;
        var denied = guardWrite(request, "unicast", target);
        if (denied != null) return denied;
        if (id == null || uuid == null || message == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "missing 'broadcasterId', 'uuid', or 'message' field"));
        }
        var principalName = resolvePrincipalName(request);
        var success = admin.framework().unicast(id, uuid, message);
        admin.auditLog().record(principalName, "unicast", target, success, message);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "unicast sent"));
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/broadcasters/destroy")
    public ResponseEntity<Map<String, Object>> destroyBroadcaster(
            HttpServletRequest request,
            @RequestParam("id") String id) {
        var denied = guardWrite(request, "destroy_broadcaster", id);
        if (denied != null) return denied;
        var principalName = resolvePrincipalName(request);
        var success = admin.framework().destroyBroadcaster(id);
        admin.auditLog().record(principalName, "destroy_broadcaster", id, success, null);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "broadcaster destroyed"));
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/resources/{uuid}")
    public ResponseEntity<Map<String, Object>> disconnectResource(
            HttpServletRequest request,
            @PathVariable("uuid") String uuid) {
        var denied = guardWrite(request, "disconnect", uuid);
        if (denied != null) return denied;
        var principalName = resolvePrincipalName(request);
        var success = admin.framework().disconnectResource(uuid);
        admin.auditLog().record(principalName, "disconnect", uuid, success, null);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "resource disconnected"));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/resources/{uuid}/resume")
    public ResponseEntity<Map<String, Object>> resumeResource(
            HttpServletRequest request,
            @PathVariable("uuid") String uuid) {
        var denied = guardWrite(request, "resume", uuid);
        if (denied != null) return denied;
        var principalName = resolvePrincipalName(request);
        var success = admin.framework().resumeResource(uuid);
        admin.auditLog().record(principalName, "resume", uuid, success, null);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "resource resumed"));
        }
        return ResponseEntity.notFound().build();
    }

    // ── A2A Task Write Operations ──

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(
            HttpServletRequest request,
            @PathVariable("taskId") String taskId) {
        var denied = guardWrite(request, "cancel_task", taskId);
        if (denied != null) return denied;
        TaskController controller = admin.taskController();
        if (controller == null) {
            return ResponseEntity.notFound().build();
        }
        var principalName = resolvePrincipalName(request);
        var success = controller.cancelTask(taskId);
        admin.auditLog().record(principalName, "cancel_task", taskId, success, null);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "task canceled"));
        }
        return ResponseEntity.notFound().build();
    }

    // ── Agents ──

    @GetMapping("/agents")
    public List<Map<String, Object>> listAgents() {
        return admin.agents().listAgents();
    }

    @GetMapping("/agents/{name}")
    public ResponseEntity<Map<String, Object>> getAgent(@PathVariable("name") String name) {
        return admin.agents().getAgent(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/agents/{name}/sessions")
    public List<Map<String, Object>> listAgentSessions(@PathVariable("name") String name) {
        return admin.agents().listSessions(name);
    }

    // ── Coordinators ──

    @GetMapping("/coordinators")
    public ResponseEntity<List<Map<String, Object>>> listCoordinators() {
        CoordinatorController controller = admin.coordinatorController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listCoordinators());
    }

    @GetMapping("/coordinators/{name}/fleet")
    public ResponseEntity<Map<String, Object>> getFleet(@PathVariable("name") String name) {
        CoordinatorController controller = admin.coordinatorController();
        if (controller == null) {
            return ResponseEntity.notFound().build();
        }
        return controller.getFleet(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/journal")
    public ResponseEntity<List<Map<String, Object>>> queryJournal(
            @RequestParam(value = "coordinationId", required = false) String coordinationId,
            @RequestParam(value = "agent", required = false) String agentName,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        CoordinatorController controller = admin.coordinatorController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        Instant sinceInstant = null;
        Instant untilInstant = null;
        try {
            if (since != null) sinceInstant = Instant.parse(since);
            if (until != null) untilInstant = Instant.parse(until);
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(List.of(Map.of(
                    "error", "Invalid timestamp: " + e.getMessage())));
        }
        return ResponseEntity.ok(
                controller.queryJournal(coordinationId, agentName, sinceInstant, untilInstant, limit));
    }

    @GetMapping("/journal/{coordinationId}")
    public ResponseEntity<List<Map<String, Object>>> getJournalEvents(
            @PathVariable("coordinationId") String coordinationId) {
        CoordinatorController controller = admin.coordinatorController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(
                controller.queryJournal(coordinationId, null, null, null, 0));
    }

    @GetMapping("/journal/{coordinationId}/log")
    public ResponseEntity<String> getJournalLog(
            @PathVariable("coordinationId") String coordinationId) {
        CoordinatorController controller = admin.coordinatorController();
        if (controller == null) {
            return ResponseEntity.notFound().build();
        }
        return controller.getJournalLog(coordinationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Agent-to-Agent Flow Graph ──

    @GetMapping("/flow")
    public ResponseEntity<Map<String, Object>> renderFlow(
            @RequestParam(value = "lookbackMinutes", defaultValue = "0") int lookbackMinutes) {
        FlowController controller = admin.flowController();
        if (controller == null) {
            return ResponseEntity.ok(Map.of("nodes", List.of(), "edges", List.of()));
        }
        return ResponseEntity.ok(controller.renderFlow(lookbackMinutes));
    }

    @GetMapping("/flow/{coordinationId}")
    public ResponseEntity<Map<String, Object>> renderRun(
            @PathVariable("coordinationId") String coordinationId) {
        FlowController controller = admin.flowController();
        if (controller == null) {
            return ResponseEntity.ok(Map.of("nodes", List.of(), "edges", List.of()));
        }
        return ResponseEntity.ok(controller.renderRun(coordinationId));
    }

    // ── A2A Tasks ──

    @GetMapping("/tasks")
    public ResponseEntity<List<Map<String, Object>>> listTasks(
            @RequestParam(value = "contextId", required = false) String contextId) {
        TaskController controller = admin.taskController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listTasks(contextId));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable("taskId") String taskId) {
        TaskController controller = admin.taskController();
        if (controller == null) {
            return ResponseEntity.notFound().build();
        }
        return controller.getTask(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── AI Runtimes ──

    @GetMapping("/runtimes")
    public ResponseEntity<List<Map<String, Object>>> listRuntimes() {
        AiRuntimeController controller = admin.aiRuntimeController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listRuntimes());
    }

    @GetMapping("/runtimes/active")
    public ResponseEntity<Map<String, Object>> getActiveRuntime() {
        AiRuntimeController controller = admin.aiRuntimeController();
        if (controller == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(controller.getActiveRuntime());
    }

    // ── Governance Policies ──

    @GetMapping("/governance/policies")
    public ResponseEntity<List<Map<String, Object>>> listGovernancePolicies() {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listPolicies());
    }

    @GetMapping("/governance/summary")
    public ResponseEntity<Map<String, Object>> governanceSummary() {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return ResponseEntity.ok(Map.of("policyCount", 0, "sources", List.of()));
        }
        return ResponseEntity.ok(controller.summary());
    }

    /**
     * Recent policy decisions (ring-buffered). {@code limit} defaults to 100;
     * capped at the log's configured capacity. Read-only — no authorizer
     * guard required.
     */
    @GetMapping("/governance/decisions")
    public ResponseEntity<List<Map<String, Object>>> governanceDecisions(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listRecentDecisions(limit));
    }

    /**
     * OWASP Agentic AI Top 10 (Dec 2025) self-assessment. Read-only. Pairs
     * with the {@code agt verify} CLI payload shape — external compliance
     * tooling that targets MS's Agent Compliance package can consume this
     * endpoint as the Atmosphere equivalent evidence source.
     */
    @GetMapping("/governance/owasp")
    public ResponseEntity<Map<String, Object>> governanceOwasp() {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return ResponseEntity.ok(Map.of("framework", "OWASP Agentic AI Top 10 (December 2025)",
                    "rows", List.of(), "total_rows", 0));
        }
        return ResponseEntity.ok(controller.owaspMatrix());
    }

    /**
     * Microsoft Agent Governance Toolkit {@code POST /check}-compatible
     * decision endpoint. External gateways (Envoy, Kong, Azure APIM)
     * that already speak to MS's ASGI policy provider can point at
     * this endpoint to use Atmosphere as the decision service.
     * Payload: {@code {"agent_id": "...", "action": "...", "context": {...}}}.
     * Response: {@code {"allowed": bool, "decision": "...", "reason": "...",
     *                   "matched_policy": "...", "evaluation_ms": N}}.
     * Read-only — no authorizer guard required (this does not mutate state).
     */
    @PostMapping("/governance/check")
    public ResponseEntity<Map<String, Object>> governanceCheck(@RequestBody(required = false) Map<String, Object> payload) {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return ResponseEntity.ok(Map.of(
                    "allowed", true,
                    "decision", "allow",
                    "reason", "",
                    "matched_policy", null,
                    "matched_source", null,
                    "evaluation_ms", 0.0));
        }
        return ResponseEntity.ok(controller.check(payload));
    }

    // ── MCP Registry ──

    @GetMapping("/mcp/tools")
    public ResponseEntity<List<Map<String, Object>>> listMcpTools() {
        McpController controller = admin.mcpController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listTools());
    }

    @GetMapping("/mcp/resources")
    public ResponseEntity<List<Map<String, Object>>> listMcpResources() {
        McpController controller = admin.mcpController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listResources());
    }

    @GetMapping("/mcp/prompts")
    public ResponseEntity<List<Map<String, Object>>> listMcpPrompts() {
        McpController controller = admin.mcpController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listPrompts());
    }

    // ── Metrics / Observability ──

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metricsSnapshot() {
        MetricsController controller = admin.metricsController();
        if (controller == null) {
            return ResponseEntity.ok(Map.of("error", "Micrometer not on classpath"));
        }
        return ResponseEntity.ok(controller.snapshot());
    }

    @GetMapping("/metrics/all")
    public ResponseEntity<List<Map<String, Object>>> allMeters() {
        MetricsController controller = admin.metricsController();
        if (controller == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(controller.listMeters());
    }

    // ── Audit Log ──

    @GetMapping("/audit")
    public List<org.atmosphere.admin.ControlAuditLog.AuditEntry> listAuditEntries(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return admin.auditLog().entries(limit);
    }
}
