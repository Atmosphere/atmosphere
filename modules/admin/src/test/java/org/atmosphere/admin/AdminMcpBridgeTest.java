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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.mcp.registry.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AdminMcpBridge} — MCP tool registration and authorizer gating.
 */
class AdminMcpBridgeTest {

    private AtmosphereAdmin admin;
    private McpRegistry registry;
    private ControlAuthorizer authorizer;
    private AdminMcpBridge bridge;

    @BeforeEach
    void setUp() {
        var framework = mock(AtmosphereFramework.class);
        var factory = mock(BroadcasterFactory.class);
        when(framework.getBroadcasterFactory()).thenReturn(factory);
        when(factory.lookupAll()).thenReturn(Collections.emptyList());
        when(framework.getAtmosphereHandlers()).thenReturn(new LinkedHashMap<>());

        admin = new AtmosphereAdmin(framework, 100);
        registry = new McpRegistry();
        authorizer = ControlAuthorizer.ALLOW_ALL;
        bridge = new AdminMcpBridge(admin, registry, authorizer);
    }

    // ── Read tool registration ──

    @Test
    void testRegisterReadToolsRegisters8Tools() {
        bridge.registerReadTools();
        var tools = registry.tools();
        assertTrue(tools.containsKey("atmosphere_overview"));
        assertTrue(tools.containsKey("atmosphere_list_broadcasters"));
        assertTrue(tools.containsKey("atmosphere_list_resources"));
        assertTrue(tools.containsKey("atmosphere_list_agents"));
        assertTrue(tools.containsKey("atmosphere_agent_sessions"));
        assertTrue(tools.containsKey("atmosphere_list_handlers"));
        assertTrue(tools.containsKey("atmosphere_list_interceptors"));
        assertTrue(tools.containsKey("atmosphere_audit_log"));
    }

    @Test
    void testOverviewToolReturnsMap() throws Exception {
        bridge.registerReadTools();
        var tool = registry.tools().get("atmosphere_overview");
        assertNotNull(tool);
        var result = tool.handler().execute(Map.of());
        assertNotNull(result);
        assertTrue(result instanceof Map);
    }

    @Test
    void testAuditLogToolReturnsEntries() throws Exception {
        admin.auditLog().record("test", "action", "target", true, null);
        bridge.registerReadTools();
        var tool = registry.tools().get("atmosphere_audit_log");
        var result = tool.handler().execute(Map.of());
        assertTrue(result instanceof List);
        assertEquals(1, ((List<?>) result).size());
    }

    // ── Write tool registration ──

    @Test
    void testRegisterWriteToolsRegisters4Tools() {
        bridge.registerWriteTools();
        var tools = registry.tools();
        assertTrue(tools.containsKey("atmosphere_broadcast"));
        assertTrue(tools.containsKey("atmosphere_disconnect_resource"));
        assertTrue(tools.containsKey("atmosphere_destroy_broadcaster"));
        assertTrue(tools.containsKey("atmosphere_cancel_task"));
    }

    // ── Authorizer gating ──

    @Test
    void testBroadcastToolDeniedByAuthorizer() throws Exception {
        var denyAll = (ControlAuthorizer) (action, target, principal) -> false;
        var deniedBridge = new AdminMcpBridge(admin, registry, denyAll);
        deniedBridge.registerWriteTools();

        var tool = registry.tools().get("atmosphere_broadcast");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) tool.handler().execute(
                Map.of("broadcasterId", "test", "message", "hello"));
        assertEquals("unauthorized", result.get("error"));
    }

    @Test
    void testDisconnectToolDeniedByAuthorizer() throws Exception {
        var denyAll = (ControlAuthorizer) (action, target, principal) -> false;
        var deniedBridge = new AdminMcpBridge(admin, registry, denyAll);
        deniedBridge.registerWriteTools();

        var tool = registry.tools().get("atmosphere_disconnect_resource");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) tool.handler().execute(
                Map.of("uuid", "some-uuid"));
        assertEquals("unauthorized", result.get("error"));
    }

    @Test
    void testDestroyBroadcasterDeniedByAuthorizer() throws Exception {
        var denyAll = (ControlAuthorizer) (action, target, principal) -> false;
        var deniedBridge = new AdminMcpBridge(admin, registry, denyAll);
        deniedBridge.registerWriteTools();

        var tool = registry.tools().get("atmosphere_destroy_broadcaster");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) tool.handler().execute(
                Map.of("broadcasterId", "test"));
        assertEquals("unauthorized", result.get("error"));
    }

    @Test
    void testCancelTaskWithNoA2AModule() throws Exception {
        bridge.registerWriteTools();
        var tool = registry.tools().get("atmosphere_cancel_task");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) tool.handler().execute(
                Map.of("taskId", "task-1"));
        assertEquals("A2A module not available", result.get("error"));
    }

    @Test
    void testCancelTaskDeniedByAuthorizer() throws Exception {
        var denyAll = (ControlAuthorizer) (action, target, principal) -> false;
        var deniedBridge = new AdminMcpBridge(admin, registry, denyAll);
        deniedBridge.registerWriteTools();

        var tool = registry.tools().get("atmosphere_cancel_task");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) tool.handler().execute(
                Map.of("taskId", "task-1"));
        assertEquals("unauthorized", result.get("error"));
    }

    // ── Optional subsystem MCP tools ──

    @Test
    @SuppressWarnings("unchecked")
    void testOptionalCoordinatorToolsRegistered() {
        var coordinator = new Object() {
            @SuppressWarnings("unused")
            public List<Map<String, Object>> listCoordinators() {
                return List.of(Map.of("name", "c1"));
            }
        };
        admin.setCoordinatorController(coordinator);
        bridge.registerReadTools();
        assertTrue(registry.tools().containsKey("atmosphere_list_coordinators"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testOptionalTaskToolsRegistered() {
        var taskCtrl = new Object() {
            @SuppressWarnings("unused")
            public List<Map<String, Object>> listTasks(String filter) {
                return List.of();
            }
        };
        admin.setTaskController(taskCtrl);
        bridge.registerReadTools();
        assertTrue(registry.tools().containsKey("atmosphere_list_tasks"));
    }

    @Test
    void testNoOptionalToolsWithoutControllers() {
        bridge.registerReadTools();
        // Should not contain optional tools
        assertEquals(8, registry.tools().size());
    }

    // ── Write tool produces audit entry ──

    @Test
    void testBroadcastToolCreatesAuditEntry() throws Exception {
        bridge.registerWriteTools();
        var tool = registry.tools().get("atmosphere_broadcast");
        tool.handler().execute(
                Map.of("broadcasterId", "test-b", "message", "hi"));
        var entries = admin.auditLog().entries();
        assertEquals(1, entries.size());
        assertEquals("broadcast", entries.getFirst().action());
    }
}
