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
import org.atmosphere.admin.ai.GovernanceController;
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
     * <p>Resolved via {@code ConfigProvider} on every {@code guardWrite}
     * call rather than at construction — MicroProfile Config supports
     * dynamic config sources, so an operator can flip the flag at
     * runtime (emergency lockdown) and every subsequent request
     * rejects without an app restart. Unit tests override by setting
     * the package-private {@code writeEnabledOverride} field.</p>
     *
     * <p>Not a MicroProfile {@code @ConfigProperty} binding — importing
     * that annotation pulls in an OSGi bundle annotation whose class
     * file is intentionally absent from the Quarkus extension jar and
     * turns compiler warnings into {@code -Werror} failures.</p>
     */
    /* package */ Boolean writeEnabledOverride;

    private boolean writeEnabled() {
        if (writeEnabledOverride != null) {
            return writeEnabledOverride;
        }
        try {
            return Boolean.parseBoolean(
                    org.eclipse.microprofile.config.ConfigProvider.getConfig()
                            .getOptionalValue("atmosphere.admin.http-write-enabled", String.class)
                            .orElse("false"));
        } catch (RuntimeException e) {
            // No MicroProfile Config backend available (unit tests, embedded
            // fixtures). Default deny per Correctness Invariant #6.
            return false;
        }
    }

    /**
     * Three-gate write authorization mirrored from the Spring Boot
     * AtmosphereAdminEndpoint.guardWrite: feature flag → Principal →
     * installed {@link org.atmosphere.admin.ControlAuthorizer}. Every
     * decision (grant and deny) lands in the audit log. Returns
     * {@code null} when the request is allowed; returns a populated
     * {@link Response} (401 or 403) when rejected.
     *
     * <p>Principal resolution checks four sources in order — same
     * chain as the Spring side, so operators who authenticate via
     * Atmosphere's own {@code AuthInterceptor} and receive the
     * principal as a request attribute (not via Jakarta Security) are
     * admitted identically across starters (Correctness Invariant #7 —
     * mode parity):</p>
     * <ol>
     *   <li>Jakarta REST {@link SecurityContext#getUserPrincipal()} —
     *       Quarkus Security's primary surface.</li>
     *   <li>{@code org.atmosphere.auth.principal} request attribute —
     *       populated by Atmosphere's {@code AuthInterceptor} on
     *       {@code X-Atmosphere-Auth} token validation (fires on
     *       Atmosphere-handled paths — won't fire on raw JAX-RS
     *       resources).</li>
     *   <li>{@code ai.userId} request attribute — set by the AI
     *       pipeline for framework-scoped identity.</li>
     *   <li>{@code X-Atmosphere-Auth} header validated against
     *       {@code atmosphere.admin.auth.token} config — the JAX-RS
     *       path on the Quarkus admin surface isn't Atmosphere-handled,
     *       so {@code AuthInterceptor} doesn't fire. This last source
     *       gives operators a one-config-line way to run the admin
     *       plane with token auth without standing up Quarkus Security,
     *       matching the demo-token posture the Spring sample uses.</li>
     * </ol>
     */
    @Context
    jakarta.servlet.http.HttpServletRequest servletRequest;

    private Response guardWrite(SecurityContext sec, String action, String target) {
        if (!writeEnabled()) {
            admin.auditLog().record(null, action + ".denied.flag", target, false,
                    "atmosphere.admin.http-write-enabled=false");
            return Response.status(403)
                    .entity(Map.of(
                            "error", "Admin write operations disabled",
                            "hint", "Set atmosphere.admin.http-write-enabled=true to enable"))
                    .build();
        }
        var principalName = resolvePrincipalName(sec);
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

    /**
     * Resolve the authenticated caller's name across the four
     * supported auth surfaces. Returns {@code null} for anonymous or
     * blank identity.
     *
     * <p>The {@code servletRequest} attribute path is best-effort on
     * Quarkus because resteasy-reactive dispatches on Vert.x threads
     * where Undertow's servlet context is not active; accessing the
     * proxy throws {@code IllegalStateException: UT000048 No request
     * is currently active}. That is a no-op for the admin plane —
     * Atmosphere's {@code AuthInterceptor} (which stashes the
     * attributes) runs on the Atmosphere servlet handler, not on the
     * JAX-RS admin resource — so on resteasy-reactive the attributes
     * are never populated. Swallow the exception and fall through to
     * the Jakarta-REST-native header source below.</p>
     */
    String resolvePrincipalName(SecurityContext sec) {
        if (sec != null && sec.getUserPrincipal() != null
                && sec.getUserPrincipal().getName() != null
                && !sec.getUserPrincipal().getName().isBlank()) {
            return sec.getUserPrincipal().getName();
        }
        try {
            if (servletRequest != null) {
                var attr = servletRequest.getAttribute("org.atmosphere.auth.principal");
                if (attr instanceof java.security.Principal p
                        && p.getName() != null && !p.getName().isBlank()) {
                    return p.getName();
                }
                if (servletRequest.getAttribute("ai.userId") instanceof String s
                        && !s.isBlank()) {
                    return s;
                }
            }
        } catch (IllegalStateException servletInactive) {
            // Vert.x thread — Undertow servlet context isn't active.
            // Attribute paths can't fire here; the header path below
            // does, so continue rather than propagate.
        }
        // Fourth source: X-Atmosphere-Auth header, validated against
        // the configured admin token via Jakarta REST's HttpHeaders
        // (populated on both Undertow and Vert.x request paths).
        var tokenAuth = resolvePrincipalFromAdminToken();
        if (tokenAuth != null) {
            return tokenAuth;
        }
        return null;
    }

    @Context
    jakarta.ws.rs.core.HttpHeaders jaxrsHeaders;

    /**
     * Match {@code X-Atmosphere-Auth} against the configured admin
     * token. Returns the logical name {@code "atmosphere-admin-token"}
     * on match so {@link org.atmosphere.admin.ControlAuthorizer}
     * implementations can grant or deny specifically by token-auth
     * principals. Returns {@code null} when no token is configured or
     * the header does not match.
     *
     * <p>Reads via {@link jakarta.ws.rs.core.HttpHeaders} rather than
     * {@code HttpServletRequest} so the path works on both the
     * Undertow servlet transport and resteasy-reactive's Vert.x
     * transport — same JAX-RS injection, same behaviour.</p>
     */
    private String resolvePrincipalFromAdminToken() {
        if (jaxrsHeaders == null) {
            return null;
        }
        String header = jaxrsHeaders.getHeaderString("X-Atmosphere-Auth");
        if (header == null || header.isBlank()) {
            return null;
        }
        String configured;
        try {
            configured = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("atmosphere.admin.auth.token", String.class)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        // Constant-time compare — no short-circuit timing leak on prefix match.
        return constantTimeEquals(header, configured)
                ? "atmosphere-admin-token"
                : null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
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
        var principalName = resolvePrincipalName(sec);
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
        var principalName = resolvePrincipalName(sec);
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
        var principalName = resolvePrincipalName(sec);
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
    public Response queryJournal(
            @QueryParam("coordinationId") String coordinationId,
            @QueryParam("agent") String agentName,
            @QueryParam("since") String since,
            @QueryParam("until") String until,
            @QueryParam("limit") Integer limit) {
        CoordinatorController controller = admin.coordinatorController();
        if (controller == null) {
            return Response.ok(List.of()).build();
        }
        Instant sinceInstant = null;
        Instant untilInstant = null;
        try {
            if (since != null) sinceInstant = Instant.parse(since);
            if (until != null) untilInstant = Instant.parse(until);
        } catch (java.time.format.DateTimeParseException e) {
            // Match the Spring starter's 400 response for the same
            // input class — malformed ISO-8601 timestamps are client
            // errors (Correctness Invariant #4: return 400 for
            // malformed input). Previously this path returned 200 with
            // a single-item error array, which masked the failure and
            // broke Spring/Quarkus API parity.
            return Response.status(400).entity(Map.of(
                    "error", "Invalid timestamp: " + e.getMessage())).build();
        }
        return Response.ok(controller.queryJournal(coordinationId, agentName,
                sinceInstant, untilInstant, limit != null ? limit : 100)).build();
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
        var principalName = resolvePrincipalName(sec);
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

    // ── Governance policy plane ───────────────────────────────────────────
    // Mirrors the Spring Boot endpoints in AtmosphereAdminEndpoint —
    // Mode Parity invariant #7. Mutating endpoints go through the same
    // guardWrite(SecurityContext, action, target) the rest of this class
    // uses; read endpoints are open like their Spring counterparts.

    @GET
    @Path("/governance/policies")
    public List<Map<String, Object>> listGovernancePolicies() {
        GovernanceController controller = admin.governanceController();
        return controller != null ? controller.listPolicies() : List.of();
    }

    @GET
    @Path("/governance/summary")
    public Map<String, Object> governanceSummary() {
        GovernanceController controller = admin.governanceController();
        return controller != null
                ? controller.summary()
                : Map.of("policyCount", 0, "sources", List.of());
    }

    @GET
    @Path("/governance/health")
    public Map<String, Object> governanceHealth() {
        GovernanceController controller = admin.governanceController();
        return controller != null
                ? controller.healthMap()
                : Map.of("killSwitch", Map.of("armed", false),
                        "policies", List.of(), "dryRuns", List.of(), "slos", List.of());
    }

    @GET
    @Path("/governance/decisions")
    public List<Map<String, Object>> governanceDecisions(@QueryParam("limit") Integer limit) {
        GovernanceController controller = admin.governanceController();
        return controller != null
                ? controller.listRecentDecisions(limit != null ? limit : 100)
                : List.of();
    }

    @GET
    @Path("/governance/owasp")
    public Map<String, Object> governanceOwasp() {
        GovernanceController controller = admin.governanceController();
        return controller != null
                ? controller.owaspMatrix()
                : Map.of("framework", "OWASP Agentic AI Top 10 (December 2025)",
                        "rows", List.of(), "total_rows", 0);
    }

    @GET
    @Path("/governance/compliance")
    public Map<String, Object> governanceCompliance() {
        GovernanceController controller = admin.governanceController();
        return controller != null ? controller.complianceMatrices() : Map.of();
    }

    @GET
    @Path("/governance/agt-verify")
    public Map<String, Object> governanceAgtVerify() {
        GovernanceController controller = admin.governanceController();
        return controller != null
                ? controller.agtVerifyExport()
                : Map.of("schemaVersion", "agt-verify/1",
                        "findings", List.of(), "summary", Map.of());
    }

    @GET
    @Path("/governance/commitments")
    public List<?> governanceCommitments(@QueryParam("limit") Integer limit) {
        CoordinatorController controller = admin.coordinatorController();
        return controller != null
                ? controller.listCommitmentRecords(limit != null ? limit : 100)
                : List.of();
    }

    @POST
    @Path("/governance/check")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response governanceCheck(Map<String, Object> body) {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            // Mode Parity with Spring: return the MS /check-compat allow
            // payload rather than 503. Gateways pointed at /governance/check
            // keep routing correctly whether or not governance is wired.
            return Response.ok(GovernanceController.unconfiguredAllowPayload()).build();
        }
        return Response.ok(controller.check(body != null ? body : Map.of())).build();
    }

    @POST
    @Path("/governance/reload")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response governanceReload(@Context SecurityContext sec, Map<String, Object> body) {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return Response.status(503)
                    .entity(Map.of("error", "governance controller not installed")).build();
        }
        var swapName = stringField(body, "swapName");
        var denied = guardWrite(sec, "governance.reload", swapName);
        if (denied != null) return denied;

        var yaml = stringField(body, "yaml");
        var principalName = resolvePrincipalName(sec);
        try {
            var result = controller.reloadSwappable(swapName, yaml);
            admin.auditLog().record(principalName, "governance.reload", swapName, true, null);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            admin.auditLog().record(principalName, "governance.reload.invalid",
                    swapName, false, e.getMessage());
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/governance/kill-switch/arm")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response governanceKillSwitchArm(@Context SecurityContext sec, Map<String, Object> body) {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return Response.status(503)
                    .entity(Map.of("error", "governance controller not installed")).build();
        }
        var reason = stringField(body, "reason");
        var denied = guardWrite(sec, "governance.kill_switch.arm", reason);
        if (denied != null) return denied;

        var operator = stringField(body, "operator");
        var principalName = resolvePrincipalName(sec);
        try {
            var result = controller.armKillSwitch(reason,
                    operator != null ? operator : principalName);
            admin.auditLog().record(principalName, "governance.kill_switch.arm",
                    reason, true, operator);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            admin.auditLog().record(principalName, "governance.kill_switch.arm.invalid",
                    reason, false, e.getMessage());
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            admin.auditLog().record(principalName, "governance.kill_switch.arm.unavailable",
                    reason, false, e.getMessage());
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/governance/kill-switch/disarm")
    public Response governanceKillSwitchDisarm(@Context SecurityContext sec) {
        GovernanceController controller = admin.governanceController();
        if (controller == null) {
            return Response.status(503)
                    .entity(Map.of("error", "governance controller not installed")).build();
        }
        var denied = guardWrite(sec, "governance.kill_switch.disarm", null);
        if (denied != null) return denied;

        var principalName = resolvePrincipalName(sec);
        try {
            var result = controller.disarmKillSwitch();
            admin.auditLog().record(principalName, "governance.kill_switch.disarm",
                    null, true, null);
            return Response.ok(result).build();
        } catch (IllegalStateException e) {
            admin.auditLog().record(principalName, "governance.kill_switch.disarm.unavailable",
                    null, false, e.getMessage());
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Safely extract a string field from a loosely-typed JSON body.
     * Missing key, null value, or non-string type → null (never the
     * literal string "null"; never {@link ClassCastException}).
     */
    private static String stringField(Map<String, Object> body, String key) {
        if (body == null) return null;
        var value = body.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }
}
