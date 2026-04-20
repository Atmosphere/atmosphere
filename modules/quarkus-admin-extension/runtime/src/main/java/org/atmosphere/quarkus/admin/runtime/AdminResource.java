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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
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

    /**
     * Feature flag — every mutating endpoint returns 403 until the
     * operator flips this to {@code true}. Default off, matching the
     * Spring Boot starter's {@code atmosphere.admin.http-write-enabled}.
     * Correctness Invariant #6: default deny on mutating surfaces.
     *
     * <p>Resolved via {@code ConfigProvider} rather than
     * {@code @ConfigProperty} to avoid pulling the MicroProfile
     * annotation class onto the extension's runtime classpath — it
     * transitively resolves an OSGi bundle annotation whose class file
     * is intentionally absent from the Quarkus extension jar.</p>
     */
    private final boolean writeEnabled = Boolean.parseBoolean(
            org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("atmosphere.admin.http-write-enabled", String.class)
                    .orElse("false"));

    /**
     * Three-gate write authorization mirrored from the Spring Boot
     * AtmosphereAdminEndpoint.guardWrite: feature flag → Jakarta
     * Security Principal → installed {@link org.atmosphere.admin.ControlAuthorizer}.
     * Every decision (grant and deny) lands in the audit log. Returns
     * {@code null} when the request is allowed; returns a populated
     * {@link Response} (401 or 403) when rejected.
     */
    private Response guardWrite(SecurityContext sec, String action, String target) {
        if (!writeEnabled) {
            admin.auditLog().record(null, action + ".denied.flag", target, false,
                    "atmosphere.admin.http-write-enabled=false");
            return Response.status(403)
                    .entity(Map.of(
                            "error", "Admin write operations disabled",
                            "hint", "Set atmosphere.admin.http-write-enabled=true to enable"))
                    .build();
        }
        String principalName = null;
        if (sec != null && sec.getUserPrincipal() != null
                && sec.getUserPrincipal().getName() != null
                && !sec.getUserPrincipal().getName().isBlank()) {
            principalName = sec.getUserPrincipal().getName();
        }
        if (principalName == null) {
            admin.auditLog().record(null, action + ".denied.anonymous", target, false, null);
            return Response.status(401)
                    .entity(Map.of("error", "Authentication required for admin write operations"))
                    .build();
        }
        var authorizer = admin.authorizer();
        if (!authorizer.authorize(action, target, principalName)) {
            admin.auditLog().record(principalName, action + ".denied.authz", target, false, null);
            return Response.status(403).entity(Map.of("error", "Forbidden")).build();
        }
        return null;
    }

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
    public Response broadcast(@Context SecurityContext sec, Map<String, String> body) {
        var id = body.get("broadcasterId");
        var message = body.get("message");
        var denied = guardWrite(sec, "broadcast", id);
        if (denied != null) return denied;
        if (id == null || message == null) {
            return Response.status(400)
                    .entity(Map.of("error", "missing 'broadcasterId' or 'message' field"))
                    .build();
        }
        var principalName = sec.getUserPrincipal().getName();
        var success = admin.framework().broadcast(id, message);
        admin.auditLog().record(principalName, "broadcast", id, success, message);
        return success
                ? Response.ok(Map.of("status", "broadcast sent")).build()
                : Response.status(404).build();
    }

    @DELETE
    @Path("/resources/{uuid}")
    public Response disconnectResource(@Context SecurityContext sec, @PathParam("uuid") String uuid) {
        var denied = guardWrite(sec, "disconnect", uuid);
        if (denied != null) return denied;
        var principalName = sec.getUserPrincipal().getName();
        var success = admin.framework().disconnectResource(uuid);
        admin.auditLog().record(principalName, "disconnect", uuid, success, null);
        return success
                ? Response.ok(Map.of("status", "resource disconnected")).build()
                : Response.status(404).build();
    }

    @POST
    @Path("/resources/{uuid}/resume")
    public Response resumeResource(@Context SecurityContext sec, @PathParam("uuid") String uuid) {
        var denied = guardWrite(sec, "resume", uuid);
        if (denied != null) return denied;
        var principalName = sec.getUserPrincipal().getName();
        var success = admin.framework().resumeResource(uuid);
        admin.auditLog().record(principalName, "resume", uuid, success, null);
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
        Instant sinceInstant = null;
        Instant untilInstant = null;
        try {
            if (since != null) sinceInstant = Instant.parse(since);
            if (until != null) untilInstant = Instant.parse(until);
        } catch (java.time.format.DateTimeParseException e) {
            return java.util.List.of(java.util.Map.of(
                    "error", "Invalid timestamp: " + e.getMessage()));
        }
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
    public Response cancelTask(@Context SecurityContext sec, @PathParam("taskId") String taskId) {
        var denied = guardWrite(sec, "cancel_task", taskId);
        if (denied != null) return denied;
        TaskController controller = admin.taskController();
        if (controller == null) {
            return Response.status(404).build();
        }
        var principalName = sec.getUserPrincipal().getName();
        var success = controller.cancelTask(taskId);
        admin.auditLog().record(principalName, "cancel_task", taskId, success, null);
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
