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
package org.atmosphere.quarkus.admin.runtime;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.a2a.TaskController;
import org.atmosphere.admin.ai.AiRuntimeController;
import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.admin.mcp.McpController;
import org.atmosphere.admin.metrics.MetricsController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Jakarta REST resource exposing the Atmosphere Admin control plane.
 * Mirrors the Spring Boot {@code AtmosphereAdminEndpoint} for Quarkus.
 *
 * @since 4.0
 */
@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    @Inject
    AtmosphereAdmin admin;

    // ── System Overview ──

    @GET
    @Path("/overview")
    public Map<String, Object> overview() {
        return admin.overview();
    }

    // ── Framework ──

    @GET
    @Path("/broadcasters")
    public List<Map<String, Object>> listBroadcasters() {
        return admin.framework().listBroadcasters();
    }

    @GET
    @Path("/broadcasters/detail")
    public Response getBroadcaster(@QueryParam("id") String id) {
        return admin.framework().getBroadcaster(id)
                .map(b -> Response.ok(b).build())
                .orElse(Response.status(404).build());
    }

    @GET
    @Path("/resources")
    public List<Map<String, Object>> listResources() {
        return admin.framework().listResources();
    }

    @GET
    @Path("/handlers")
    public List<Map<String, Object>> listHandlers() {
        return admin.framework().listHandlers();
    }

    @GET
    @Path("/interceptors")
    public List<Map<String, Object>> listInterceptors() {
        return admin.framework().listInterceptors();
    }

    // ── Framework Write Operations ──

    @POST
    @Path("/broadcasters/broadcast")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response broadcast(Map<String, String> body) {
        var id = body.get("broadcasterId");
        var message = body.get("message");
        if (id == null || message == null) {
            return Response.status(400)
                    .entity(Map.of("error", "missing 'broadcasterId' or 'message' field"))
                    .build();
        }
        var success = admin.framework().broadcast(id, message);
        admin.auditLog().record(null, "broadcast", id, success, message);
        return success
                ? Response.ok(Map.of("status", "broadcast sent")).build()
                : Response.status(404).build();
    }

    @DELETE
    @Path("/resources/{uuid}")
    public Response disconnectResource(@PathParam("uuid") String uuid) {
        var success = admin.framework().disconnectResource(uuid);
        admin.auditLog().record(null, "disconnect", uuid, success, null);
        return success
                ? Response.ok(Map.of("status", "resource disconnected")).build()
                : Response.status(404).build();
    }

    @POST
    @Path("/resources/{uuid}/resume")
    public Response resumeResource(@PathParam("uuid") String uuid) {
        var success = admin.framework().resumeResource(uuid);
        admin.auditLog().record(null, "resume", uuid, success, null);
        return success
                ? Response.ok(Map.of("status", "resource resumed")).build()
                : Response.status(404).build();
    }

    // ── Agents ──

    @GET
    @Path("/agents")
    public List<Map<String, Object>> listAgents() {
        return admin.agents().listAgents();
    }

    @GET
    @Path("/agents/{name}")
    public Response getAgent(@PathParam("name") String name) {
        return admin.agents().getAgent(name)
                .map(a -> Response.ok(a).build())
                .orElse(Response.status(404).build());
    }

    @GET
    @Path("/agents/{name}/sessions")
    public List<Map<String, Object>> listAgentSessions(@PathParam("name") String name) {
        return admin.agents().listSessions(name);
    }

    // ── Coordinators ──

    @GET
    @Path("/coordinators")
    public List<Map<String, Object>> listCoordinators() {
        CoordinatorController controller = admin.coordinatorController();
        return controller != null ? controller.listCoordinators() : List.of();
    }

    @GET
    @Path("/journal")
    public List<Map<String, Object>> queryJournal(
            @QueryParam("coordinationId") String coordinationId,
            @QueryParam("agent") String agentName,
            @QueryParam("since") String since,
            @QueryParam("until") String until,
            @QueryParam("limit") Integer limit) {
        CoordinatorController controller = admin.coordinatorController();
        if (controller == null) {
            return List.of();
        }
        Instant sinceInstant = since != null ? Instant.parse(since) : null;
        Instant untilInstant = until != null ? Instant.parse(until) : null;
        return controller.queryJournal(coordinationId, agentName,
                sinceInstant, untilInstant, limit != null ? limit : 100);
    }

    // ── A2A Tasks ──

    @GET
    @Path("/tasks")
    public List<Map<String, Object>> listTasks(@QueryParam("contextId") String contextId) {
        TaskController controller = admin.taskController();
        return controller != null ? controller.listTasks(contextId) : List.of();
    }

    @GET
    @Path("/tasks/{taskId}")
    public Response getTask(@PathParam("taskId") String taskId) {
        TaskController controller = admin.taskController();
        if (controller == null) {
            return Response.status(404).build();
        }
        return controller.getTask(taskId)
                .map(t -> Response.ok(t).build())
                .orElse(Response.status(404).build());
    }

    @POST
    @Path("/tasks/{taskId}/cancel")
    public Response cancelTask(@PathParam("taskId") String taskId) {
        TaskController controller = admin.taskController();
        if (controller == null) {
            return Response.status(404).build();
        }
        var success = controller.cancelTask(taskId);
        admin.auditLog().record(null, "cancel_task", taskId, success, null);
        return success
                ? Response.ok(Map.of("status", "task canceled")).build()
                : Response.status(404).build();
    }

    // ── AI Runtimes ──

    @GET
    @Path("/runtimes")
    public List<Map<String, Object>> listRuntimes() {
        AiRuntimeController controller = admin.aiRuntimeController();
        return controller != null ? controller.listRuntimes() : List.of();
    }

    @GET
    @Path("/runtimes/active")
    public Response getActiveRuntime() {
        AiRuntimeController controller = admin.aiRuntimeController();
        return controller != null
                ? Response.ok(controller.getActiveRuntime()).build()
                : Response.status(404).build();
    }

    // ── MCP Registry ──

    @GET
    @Path("/mcp/tools")
    public List<Map<String, Object>> listMcpTools() {
        McpController controller = admin.mcpController();
        return controller != null ? controller.listTools() : List.of();
    }

    @GET
    @Path("/mcp/resources")
    public List<Map<String, Object>> listMcpResources() {
        McpController controller = admin.mcpController();
        return controller != null ? controller.listResources() : List.of();
    }

    @GET
    @Path("/mcp/prompts")
    public List<Map<String, Object>> listMcpPrompts() {
        McpController controller = admin.mcpController();
        return controller != null ? controller.listPrompts() : List.of();
    }

    // ── Metrics ──

    @GET
    @Path("/metrics")
    public Map<String, Object> metricsSnapshot() {
        MetricsController controller = admin.metricsController();
        return controller != null
                ? controller.snapshot()
                : Map.of("error", "Micrometer not on classpath");
    }

    @GET
    @Path("/metrics/all")
    public List<Map<String, Object>> allMeters() {
        MetricsController controller = admin.metricsController();
        return controller != null ? controller.listMeters() : List.of();
    }

    // ── Audit Log ──

    @GET
    @Path("/audit")
    public List<?> listAuditEntries(@QueryParam("limit") Integer limit) {
        return admin.auditLog().entries(limit != null ? limit : 100);
    }
}
