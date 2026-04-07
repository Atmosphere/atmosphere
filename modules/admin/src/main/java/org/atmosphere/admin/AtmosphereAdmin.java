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

import org.atmosphere.admin.agent.AgentController;
import org.atmosphere.admin.framework.FrameworkController;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.metrics.AtmosphereHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central facade for the Atmosphere Admin control plane. Aggregates all
 * domain-specific controllers and provides the system overview.
 *
 * <p>Optional subsystem controllers (coordinator, A2A, AI, MCP) are set via
 * setters — they are only available when the corresponding modules are on the
 * classpath.</p>
 *
 * @since 4.0
 */
public final class AtmosphereAdmin {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAdmin.class);

    private final FrameworkController frameworkController;
    private final AgentController agentController;
    private final AtmosphereHealth health;
    private final ControlAuditLog auditLog;

    // Optional controllers — null when modules are not on classpath
    private Object coordinatorController;
    private Object taskController;
    private Object aiRuntimeController;
    private Object mcpController;
    private Object metricsController;

    public AtmosphereAdmin(AtmosphereFramework framework, int auditLogSize) {
        this.frameworkController = new FrameworkController(framework);
        this.agentController = new AgentController(framework);
        this.health = new AtmosphereHealth(framework);
        this.auditLog = new ControlAuditLog(auditLogSize);
    }

    /**
     * System overview — aggregates health, agent count, and optional
     * subsystem summaries into a single dashboard payload.
     */
    public Map<String, Object> overview() {
        var result = new LinkedHashMap<String, Object>();

        // Health snapshot
        result.putAll(health.check());

        // Agent count and session count
        var agents = agentController.listAgents();
        result.put("agentCount", agents.size());
        result.put("activeSessions", agentController.totalSessionCount());

        // Optional: coordinator count
        if (coordinatorController != null) {
            try {
                var method = coordinatorController.getClass().getMethod("listCoordinators");
                var coordinators = (java.util.List<?>) method.invoke(coordinatorController);
                result.put("coordinatorCount", coordinators.size());
            } catch (Exception e) {
                logger.trace("Could not query coordinators", e);
            }
        }

        // Optional: active task count
        if (taskController != null) {
            try {
                var method = taskController.getClass().getMethod("listTasks", String.class);
                var tasks = (java.util.List<?>) method.invoke(taskController, (String) null);
                result.put("taskCount", tasks.size());
            } catch (Exception e) {
                logger.trace("Could not query tasks", e);
            }
        }

        // Optional: active AI runtime
        if (aiRuntimeController != null) {
            try {
                var method = aiRuntimeController.getClass().getMethod("getActiveRuntime");
                var runtime = (Map<?, ?>) method.invoke(aiRuntimeController);
                result.put("aiRuntime", runtime.get("name"));
            } catch (Exception e) {
                logger.trace("Could not query AI runtime", e);
            }
        }

        return result;
    }

    // ── Accessors ──

    public FrameworkController framework() {
        return frameworkController;
    }

    public AgentController agents() {
        return agentController;
    }

    public ControlAuditLog auditLog() {
        return auditLog;
    }

    public AtmosphereHealth health() {
        return health;
    }

    // ── Optional subsystem controllers ──

    public void setCoordinatorController(Object controller) {
        this.coordinatorController = controller;
    }

    @SuppressWarnings("unchecked")
    public <T> T coordinatorController() {
        return (T) coordinatorController;
    }

    public void setTaskController(Object controller) {
        this.taskController = controller;
    }

    @SuppressWarnings("unchecked")
    public <T> T taskController() {
        return (T) taskController;
    }

    public void setAiRuntimeController(Object controller) {
        this.aiRuntimeController = controller;
    }

    @SuppressWarnings("unchecked")
    public <T> T aiRuntimeController() {
        return (T) aiRuntimeController;
    }

    public void setMcpController(Object controller) {
        this.mcpController = controller;
    }

    @SuppressWarnings("unchecked")
    public <T> T mcpController() {
        return (T) mcpController;
    }

    public void setMetricsController(Object controller) {
        this.metricsController = controller;
    }

    @SuppressWarnings("unchecked")
    public <T> T metricsController() {
        return (T) metricsController;
    }
}
