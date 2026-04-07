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
import org.atmosphere.admin.coordinator.CoordinatorController;
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

    public AtmosphereAdminEndpoint(AtmosphereAdmin admin) {
        this.admin = admin;
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
            @RequestBody Map<String, String> body) {
        var id = body.get("broadcasterId");
        var message = body.get("message");
        if (id == null || message == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "missing 'broadcasterId' or 'message' field"));
        }
        var success = admin.framework().broadcast(id, message);
        admin.auditLog().record(null, "broadcast", id, success, message);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "broadcast sent"));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/broadcasters/unicast")
    public ResponseEntity<Map<String, Object>> unicast(
            @RequestBody Map<String, String> body) {
        var id = body.get("broadcasterId");
        var uuid = body.get("uuid");
        var message = body.get("message");
        if (id == null || uuid == null || message == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "missing 'broadcasterId', 'uuid', or 'message' field"));
        }
        var success = admin.framework().unicast(id, uuid, message);
        admin.auditLog().record(null, "unicast", id + "/" + uuid, success, message);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "unicast sent"));
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/broadcasters/destroy")
    public ResponseEntity<Map<String, Object>> destroyBroadcaster(
            @RequestParam("id") String id) {
        var success = admin.framework().destroyBroadcaster(id);
        admin.auditLog().record(null, "destroy_broadcaster", id, success, null);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "broadcaster destroyed"));
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/resources/{uuid}")
    public ResponseEntity<Map<String, Object>> disconnectResource(@PathVariable("uuid") String uuid) {
        var success = admin.framework().disconnectResource(uuid);
        admin.auditLog().record(null, "disconnect", uuid, success, null);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "resource disconnected"));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/resources/{uuid}/resume")
    public ResponseEntity<Map<String, Object>> resumeResource(@PathVariable("uuid") String uuid) {
        var success = admin.framework().resumeResource(uuid);
        admin.auditLog().record(null, "resume", uuid, success, null);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "resource resumed"));
        }
        return ResponseEntity.notFound().build();
    }

    // ── A2A Task Write Operations ──

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable("taskId") String taskId) {
        TaskController controller = admin.taskController();
        if (controller == null) {
            return ResponseEntity.notFound().build();
        }
        var success = controller.cancelTask(taskId);
        admin.auditLog().record(null, "cancel_task", taskId, success, null);
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
        Instant sinceInstant = since != null ? Instant.parse(since) : null;
        Instant untilInstant = until != null ? Instant.parse(until) : null;
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
