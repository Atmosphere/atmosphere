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
        registry.registerTool("atmosphere_broadcast",
                "Broadcast a message to all subscribers of a specific broadcaster",
                List.of(
                        new ParamEntry("broadcasterId", "Target broadcaster ID", true, String.class),
                        new ParamEntry("message", "Message to broadcast", true, String.class)),
                args -> {
                    var id = (String) args.get("broadcasterId");
                    var msg = (String) args.get("message");
                    if (!authorizer.authorize("broadcast", id, null)) {
                        return Map.of("error", "unauthorized");
                    }
                    var success = admin.framework().broadcast(id, msg);
                    admin.auditLog().record("mcp", "broadcast", id, success, msg);
                    return Map.of("success", success);
                });

        registry.registerTool("atmosphere_disconnect_resource",
                "Disconnect a specific client by resource UUID",
                List.of(new ParamEntry("uuid", "Resource UUID to disconnect", true, String.class)),
                args -> {
                    var uuid = (String) args.get("uuid");
                    if (!authorizer.authorize("disconnect", uuid, null)) {
                        return Map.of("error", "unauthorized");
                    }
                    var success = admin.framework().disconnectResource(uuid);
                    admin.auditLog().record("mcp", "disconnect", uuid, success, null);
                    return Map.of("success", success);
                });

        registry.registerTool("atmosphere_destroy_broadcaster",
                "Destroy a broadcaster and disconnect all its subscribers",
                List.of(new ParamEntry("broadcasterId", "Broadcaster ID to destroy", true, String.class)),
                args -> {
                    var id = (String) args.get("broadcasterId");
                    if (!authorizer.authorize("destroy_broadcaster", id, null)) {
                        return Map.of("error", "unauthorized");
                    }
                    var success = admin.framework().destroyBroadcaster(id);
                    admin.auditLog().record("mcp", "destroy_broadcaster", id, success, null);
                    return Map.of("success", success);
                });

        registry.registerTool("atmosphere_cancel_task",
                "Cancel an in-flight A2A task",
                List.of(new ParamEntry("taskId", "Task ID to cancel", true, String.class)),
                args -> {
                    var taskId = (String) args.get("taskId");
                    if (!authorizer.authorize("cancel_task", taskId, null)) {
                        return Map.of("error", "unauthorized");
                    }
                    var controller = admin.<org.atmosphere.admin.a2a.TaskController>taskController();
                    if (controller == null) {
                        return Map.of("error", "A2A module not available");
                    }
                    var success = controller.cancelTask(taskId);
                    admin.auditLog().record("mcp", "cancel_task", taskId, success, null);
                    return Map.of("success", success);
                });

        logger.info("Atmosphere Admin: registered 4 write MCP tools (authorizer-gated)");
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
    }
}
