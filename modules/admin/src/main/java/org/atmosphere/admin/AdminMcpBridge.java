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
package org.atmosphere.admin;

import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.registry.McpRegistry.ParamEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Registers Atmosphere Admin control plane operations as MCP tools,
 * enabling AI-manages-AI scenarios where an operator agent can inspect
 * and control the fleet.
 *
 * <p>Read tools are always registered. Write tools are registered only
 * when {@code enableWriteTools} is true and are gated by the
 * {@link ControlAuthorizer}.</p>
 *
 * @since 4.0
 */
public final class AdminMcpBridge {

    private static final Logger logger = LoggerFactory.getLogger(AdminMcpBridge.class);

    private final AtmosphereAdmin admin;
    private final McpRegistry registry;
    private final ControlAuthorizer authorizer;

    public AdminMcpBridge(AtmosphereAdmin admin, McpRegistry registry,
                           ControlAuthorizer authorizer) {
        this.admin = admin;
        this.registry = registry;
        this.authorizer = authorizer;
    }

    /**
     * Register all read-only MCP tools.
     */
    public void registerReadTools() {
        registry.registerTool("atmosphere_overview",
                "Get system overview: connections, broadcasters, agents, tasks, runtime status",
                args -> admin.overview());

        registry.registerTool("atmosphere_list_broadcasters",
                "List all active Atmosphere broadcasters with ID, class, and resource count",
                args -> admin.framework().listBroadcasters());

        registry.registerTool("atmosphere_list_resources",
                "List all connected resources (WebSocket/SSE clients) with UUID, transport, and status",
                args -> admin.framework().listResources());

        registry.registerTool("atmosphere_list_agents",
                "List all registered @Agent endpoints with name, version, and protocol support",
                args -> admin.agents().listAgents());

        registry.registerTool("atmosphere_agent_sessions",
                "List active sessions for a specific agent",
                List.of(new ParamEntry("name", "Agent name", true, String.class)),
                args -> {
                    var name = (String) args.get("name");
                    return admin.agents().listSessions(name);
                });

        registry.registerTool("atmosphere_list_handlers",
                "List all registered Atmosphere handlers with their URL paths",
                args -> admin.framework().listHandlers());

        registry.registerTool("atmosphere_list_interceptors",
                "List all interceptors in the Atmosphere processing chain",
                args -> admin.framework().listInterceptors());

        registry.registerTool("atmosphere_audit_log",
                "List recent control plane audit log entries",
                args -> admin.auditLog().entries(100));

        // Optional subsystem tools
        registerOptionalReadTools();

        logger.info("Atmosphere Admin: registered {} read MCP tools", 8);
    }

    /**
     * Register write MCP tools (destructive operations, gated by authorizer).
     */
    public void registerWriteTools() {
        // Each write tool reads the authenticated MCP caller's principal from
        // the identity-aware 2-arg ToolHandler contract — passing it straight
        // through to ControlAuthorizer.authorize so the default
        // REQUIRE_PRINCIPAL gate actually fires. The earlier null-principal
        // form either permanently denied every call (under REQUIRE_PRINCIPAL)
        // or opened every call (under ALLOW_ALL) depending on wiring.
        registry.registerTool("atmosphere_broadcast",
                "Broadcast a message to all subscribers of a specific broadcaster",
                List.of(
                        new ParamEntry("broadcasterId", "Target broadcaster ID", true, String.class),
                        new ParamEntry("message", "Message to broadcast", true, String.class)),
                (McpRegistry.IdentityAwareToolHandler) (args, principal) -> {
                    var id = (String) args.get("broadcasterId");
                    var msg = (String) args.get("message");
                    if (!authorizer.authorize("broadcast", id, principal)) {
                        admin.auditLog().record(principal, "broadcast.denied", id, false, null);
                        return Map.of("error", "unauthorized");
                    }
                    var success = admin.framework().broadcast(id, msg);
                    admin.auditLog().record(principal, "broadcast", id, success, msg);
                    return Map.of("success", success);
                });

        registry.registerTool("atmosphere_disconnect_resource",
                "Disconnect a specific client by resource UUID",
                List.of(new ParamEntry("uuid", "Resource UUID to disconnect", true, String.class)),
                (McpRegistry.IdentityAwareToolHandler) (args, principal) -> {
                    var uuid = (String) args.get("uuid");
                    if (!authorizer.authorize("disconnect", uuid, principal)) {
                        admin.auditLog().record(principal, "disconnect.denied", uuid, false, null);
                        return Map.of("error", "unauthorized");
                    }
                    var success = admin.framework().disconnectResource(uuid);
                    admin.auditLog().record(principal, "disconnect", uuid, success, null);
                    return Map.of("success", success);
                });

        registry.registerTool("atmosphere_destroy_broadcaster",
                "Destroy a broadcaster and disconnect all its subscribers",
                List.of(new ParamEntry("broadcasterId", "Broadcaster ID to destroy", true, String.class)),
                (McpRegistry.IdentityAwareToolHandler) (args, principal) -> {
                    var id = (String) args.get("broadcasterId");
                    if (!authorizer.authorize("destroy_broadcaster", id, principal)) {
                        admin.auditLog().record(principal, "destroy_broadcaster.denied",
                                id, false, null);
                        return Map.of("error", "unauthorized");
                    }
                    var success = admin.framework().destroyBroadcaster(id);
                    admin.auditLog().record(principal, "destroy_broadcaster", id, success, null);
                    return Map.of("success", success);
                });

        registry.registerTool("atmosphere_cancel_task",
                "Cancel an in-flight A2A task",
                List.of(new ParamEntry("taskId", "Task ID to cancel", true, String.class)),
                (McpRegistry.IdentityAwareToolHandler) (args, principal) -> {
                    var taskId = (String) args.get("taskId");
                    if (!authorizer.authorize("cancel_task", taskId, principal)) {
                        admin.auditLog().record(principal, "cancel_task.denied",
                                taskId, false, null);
                        return Map.of("error", "unauthorized");
                    }
                    var controller = admin.<org.atmosphere.admin.a2a.TaskController>taskController();
                    if (controller == null) {
                        return Map.of("error", "A2A module not available");
                    }
                    var success = controller.cancelTask(taskId);
                    admin.auditLog().record(principal, "cancel_task", taskId, success, null);
                    return Map.of("success", success);
                });

        registry.registerTool("atmosphere_resume_run",
                "Re-drive a crashed durable agent run from its journal (admin-authorized)",
                List.of(new ParamEntry("runId", "Run ID to re-drive", true, String.class)),
                (McpRegistry.IdentityAwareToolHandler) (args, principal) -> {
                    var runId = (String) args.get("runId");
                    if (!authorizer.authorize("resume_run", runId, principal)) {
                        admin.auditLog().record(principal, "resume_run.denied", runId, false, null);
                        return Map.of("error", "unauthorized");
                    }
                    var spine = org.atmosphere.ai.resume.DurableRunSpineHolder.get();
                    if (!spine.enabled()) {
                        return Map.of("error", "durable runs are not enabled");
                    }
                    var session = new org.atmosphere.ai.resume.CapturingRunSession(runId);
                    var status = new org.atmosphere.ai.resume.DurableRunResumer(spine)
                            .resumeAsAdmin(runId, session);
                    var success = status == org.atmosphere.ai.resume.DurableRunResumer.Status.RESUMED;
                    admin.auditLog().record(principal, "resume_run", runId, success, status.name());
                    return Map.of("status", status.name(), "output", session.text());
                });

        // Session tape read tool — optional AI module, registered like the
        // other optional-module tools.
        registerTapeReadTool();

        logger.info("Atmosphere Admin: registered 5 write MCP tools (authorizer-gated)");
    }

    /**
     * Register the {@code atmosphere_read_tape} tool. The tool is read-only,
     * but tape content is recorded pre-redaction, so it stays on the
     * authorizer-gated surface (default deny — Correctness Invariant #6)
     * rather than the ALLOW_ALL read surface. The store is resolved at call
     * time from the AI module's {@code TapeSupport} holder; when no tape is
     * installed the tool reports it as disabled (runtime truth — Correctness
     * Invariant #5). Registration is skipped when the optional AI module is
     * not on the classpath. AI types appear only inside handler bodies, never
     * in method signatures, so reflecting over this class without the AI
     * module present cannot trigger a {@code NoClassDefFoundError}.
     */
    private void registerTapeReadTool() {
        try {
            Class.forName("org.atmosphere.ai.tape.TapeSupport");
        } catch (ClassNotFoundException e) {
            logger.trace("Could not register tape MCP tool", e);
            return;
        }

        registry.registerTool("atmosphere_read_tape",
                "Read the session tape: list recorded AI runs by tapeId/status, or cursor-read "
                        + "one run's steps with (runId, fromSeq, max)",
                List.of(
                        new ParamEntry("runId", "Run ID to read steps from (omit to list runs)",
                                false, String.class),
                        new ParamEntry("fromSeq", "First step sequence number to read (default 0)",
                                false, String.class),
                        new ParamEntry("max", "Max steps to return; <= 0 for no cap (default 100)",
                                false, String.class),
                        new ParamEntry("tapeId", "Filter runs by conversation tape ID",
                                false, String.class),
                        new ParamEntry("status", "Filter runs by status: OPEN, COMPLETED, ERROR, "
                                + "CANCELLED or ABANDONED", false, String.class),
                        new ParamEntry("limit", "Max runs to list (default 50)",
                                false, String.class)),
                (McpRegistry.IdentityAwareToolHandler) (args, principal) -> {
                    var runId = (String) args.get("runId");
                    var tapeId = (String) args.get("tapeId");
                    var target = runId != null ? runId : (tapeId != null ? tapeId : "*");
                    if (!authorizer.authorize("read_tape", target, principal)) {
                        admin.auditLog().record(principal, "read_tape.denied", target, false, null);
                        return Map.of("error", "unauthorized");
                    }
                    var store = org.atmosphere.ai.tape.TapeSupport.installedStore();
                    if (store.isEmpty()) {
                        return Map.of("error", "session tape is not enabled");
                    }
                    try {
                        if (runId != null) {
                            var fromSeqStr = (String) args.get("fromSeq");
                            var maxStr = (String) args.get("max");
                            long fromSeq = fromSeqStr != null ? Long.parseLong(fromSeqStr) : 0L;
                            int max = maxStr != null ? Integer.parseInt(maxStr) : 100;
                            var steps = store.get().readSteps(runId, fromSeq, max);
                            var stepList = new java.util.ArrayList<Map<String, Object>>(steps.size());
                            long nextSeq = fromSeq;
                            for (var step : steps) {
                                stepList.add(Map.of(
                                        "seq", step.seq(),
                                        "kind", step.kind(),
                                        "payload", step.payload(),
                                        "ts", step.ts()));
                                nextSeq = step.seq() + 1;
                            }
                            admin.auditLog().record(principal, "read_tape", runId, true,
                                    "steps=" + stepList.size());
                            return Map.of("runId", runId, "steps", stepList, "nextSeq", nextSeq);
                        }
                        var statusStr = (String) args.get("status");
                        var limitStr = (String) args.get("limit");
                        var status = statusStr != null
                                ? org.atmosphere.ai.tape.TapeStatus.valueOf(
                                        statusStr.trim().toUpperCase(java.util.Locale.ROOT))
                                : null;
                        int limit = limitStr != null ? Integer.parseInt(limitStr) : 50;
                        var runs = store.get().listRuns(
                                new org.atmosphere.ai.tape.TapeQuery(tapeId, status, limit));
                        var runList = new java.util.ArrayList<Map<String, Object>>(runs.size());
                        for (var run : runs) {
                            // TapeRun has nullable fields (Map.of rejects nulls).
                            var row = new java.util.LinkedHashMap<String, Object>();
                            row.put("runId", run.runId());
                            row.put("tapeId", run.tapeId());
                            row.put("sessionId", run.sessionId());
                            row.put("resourceUuid", run.resourceUuid());
                            row.put("userId", run.userId());
                            row.put("endpoint", run.endpoint());
                            row.put("model", run.model());
                            row.put("runtimeName", run.runtimeName());
                            row.put("startedAt", run.startedAt());
                            row.put("status", run.status().name());
                            row.put("endedAt", run.endedAt());
                            row.put("stepCount", run.stepCount());
                            row.put("droppedSteps", run.droppedSteps());
                            row.put("truncated", run.truncated());
                            row.put("parentRunId", run.parentRunId());
                            runList.add(row);
                        }
                        admin.auditLog().record(principal, "read_tape", target, true,
                                "runs=" + runList.size());
                        return Map.of("runs", runList);
                    } catch (IllegalArgumentException e) {
                        // Malformed cursor number or unknown status — caller
                        // input, reported as such (Correctness Invariant #4).
                        return Map.of("error", "invalid argument: " + e.getMessage());
                    }
                });

        logger.debug("Atmosphere Admin: registered tape read MCP tool (authorizer-gated)");
    }

    @SuppressWarnings("unchecked")
    private void registerOptionalReadTools() {
        // Coordinator tools
        var coordinator = admin.coordinatorController();
        if (coordinator != null) {
            try {
                var listMethod = coordinator.getClass().getMethod("listCoordinators");
                registry.registerTool("atmosphere_list_coordinators",
                        "List all coordinator fleets with agent counts",
                        args -> {
                            try {
                                return (List<Map<String, Object>>) listMethod.invoke(coordinator);
                            } catch (Exception e) {
                                return List.of();
                            }
                        });

                var queryMethod = coordinator.getClass().getMethod(
                        "queryJournal", String.class, String.class,
                        java.time.Instant.class, java.time.Instant.class, int.class);
                registry.registerTool("atmosphere_query_journal",
                        "Query the coordination journal for agent dispatch events",
                        List.of(
                                new ParamEntry("coordinationId", "Filter by coordination ID", false, String.class),
                                new ParamEntry("agent", "Filter by agent name", false, String.class),
                                new ParamEntry("limit", "Max results (default 50)", false, String.class)),
                        args -> {
                            try {
                                var coordId = (String) args.get("coordinationId");
                                var agent = (String) args.get("agent");
                                var limitStr = (String) args.get("limit");
                                int limit = limitStr != null ? Integer.parseInt(limitStr) : 50;
                                return (List<Map<String, Object>>) queryMethod.invoke(
                                        coordinator, coordId, agent, null, null, limit);
                            } catch (Exception e) {
                                return List.of();
                            }
                        });
            } catch (NoSuchMethodException e) {
                logger.trace("Could not register coordinator MCP tools", e);
            }
        }

        // Task tools
        var taskCtrl = admin.taskController();
        if (taskCtrl != null) {
            try {
                var listMethod = taskCtrl.getClass().getMethod("listTasks", String.class);
                registry.registerTool("atmosphere_list_tasks",
                        "List A2A tasks, optionally filtered by context ID",
                        List.of(new ParamEntry("contextId", "Filter by context ID", false, String.class)),
                        args -> {
                            try {
                                return (List<Map<String, Object>>) listMethod.invoke(
                                        taskCtrl, args.get("contextId"));
                            } catch (Exception e) {
                                return List.of();
                            }
                        });
            } catch (NoSuchMethodException e) {
                logger.trace("Could not register task MCP tools", e);
            }
        }

        // AI runtime tools
        var aiCtrl = admin.aiRuntimeController();
        if (aiCtrl != null) {
            try {
                var listMethod = aiCtrl.getClass().getMethod("listRuntimes");
                registry.registerTool("atmosphere_list_runtimes",
                        "List available AI runtimes with capabilities",
                        args -> {
                            try {
                                return (List<Map<String, Object>>) listMethod.invoke(aiCtrl);
                            } catch (Exception e) {
                                return List.of();
                            }
                        });

                var metricsMethod = aiCtrl.getClass().getMethod("getActiveRuntime");
                registry.registerTool("atmosphere_ai_metrics",
                        "Get the active AI runtime and its capabilities",
                        args -> {
                            try {
                                return (Map<String, Object>) metricsMethod.invoke(aiCtrl);
                            } catch (Exception e) {
                                return Map.of();
                            }
                        });
            } catch (NoSuchMethodException e) {
                logger.trace("Could not register AI MCP tools", e);
            }
        }

        // MCP registry tools
        var mcpCtrl = admin.mcpController();
        if (mcpCtrl != null) {
            try {
                var toolsMethod = mcpCtrl.getClass().getMethod("listTools");
                registry.registerTool("atmosphere_list_mcp_tools",
                        "List all registered MCP tools with descriptions and parameters",
                        args -> {
                            try {
                                return (List<Map<String, Object>>) toolsMethod.invoke(mcpCtrl);
                            } catch (Exception e) {
                                return List.of();
                            }
                        });
            } catch (NoSuchMethodException e) {
                logger.trace("Could not register MCP registry tools", e);
            }
        }

        // Verifier tools (static plan-and-verify "Guardians" stack). All three
        // are read-only: summary/examples describe the active chain, and the
        // check tool routes to VerifierController.dryCheck — it plans and
        // verifies a goal but NEVER executes it, so no tool fires from an MCP
        // read call. The mutating path (VerifierController.check, which runs a
        // clean plan) stays behind the admin write gate and is intentionally
        // not bridged here (Correctness Invariant #6).
        var verifierCtrl = admin.verifierController();
        if (verifierCtrl != null) {
            try {
                var summaryMethod = verifierCtrl.getClass().getMethod("summary");
                registry.registerTool("atmosphere_verifier_summary",
                        "Describe the active static plan-and-verify chain: verifier names, "
                                + "the resolved SMT solver, and the declarative policy "
                                + "(allowlist, taint rules, numeric invariants)",
                        args -> {
                            try {
                                return (Map<String, Object>) summaryMethod.invoke(verifierCtrl);
                            } catch (Exception e) {
                                logger.trace("atmosphere_verifier_summary invocation failed", e);
                                return Map.of();
                            }
                        });

                var examplesMethod = verifierCtrl.getClass().getMethod("examples");
                registry.registerTool("atmosphere_verifier_examples",
                        "List the example goals this deployment ships for the verifier",
                        args -> {
                            try {
                                return (List<Map<String, Object>>) examplesMethod.invoke(verifierCtrl);
                            } catch (Exception e) {
                                logger.trace("atmosphere_verifier_examples invocation failed", e);
                                return List.of();
                            }
                        });

                var dryCheckMethod = verifierCtrl.getClass().getMethod("dryCheck", String.class);
                registry.registerTool("atmosphere_verifier_check",
                        "Plan a goal and run every verifier over the resulting plan WITHOUT "
                                + "executing it. Returns the plan AST, per-verifier pass/fail "
                                + "verdicts, and the merged violations (status verified|refused). "
                                + "Read-only: no tool fires",
                        List.of(new ParamEntry("goal",
                                "The natural-language goal to plan and verify", true, String.class)),
                        args -> {
                            try {
                                return (Map<String, Object>) dryCheckMethod.invoke(
                                        verifierCtrl, args.get("goal"));
                            } catch (Exception e) {
                                logger.trace("atmosphere_verifier_check invocation failed", e);
                                return Map.of("status", "error",
                                        "error", "verifier invocation failed");
                            }
                        });
            } catch (NoSuchMethodException e) {
                logger.trace("Could not register verifier MCP tools", e);
            }
        }
    }
}
