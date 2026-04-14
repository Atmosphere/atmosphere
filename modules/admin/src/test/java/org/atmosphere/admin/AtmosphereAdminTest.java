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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AtmosphereAdmin} — the central admin facade.
 */
class AtmosphereAdminTest {

    private AtmosphereFramework framework;
    private AtmosphereAdmin admin;

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        var factory = mock(BroadcasterFactory.class);
        when(framework.getBroadcasterFactory()).thenReturn(factory);
        when(factory.lookupAll()).thenReturn(Collections.emptyList());
        when(framework.getAtmosphereHandlers()).thenReturn(new LinkedHashMap<>());
        admin = new AtmosphereAdmin(framework, 100);
    }

    // ── Constructor / accessors ──

    @Test
    void testAccessorsAreNotNull() {
        assertNotNull(admin.framework());
        assertNotNull(admin.agents());
        assertNotNull(admin.health());
        assertNotNull(admin.auditLog());
    }

    @Test
    void testNullFrameworkProducesNullControllers() {
        var nullAdmin = new AtmosphereAdmin(null, 50);
        assertNull(nullAdmin.framework());
        assertNull(nullAdmin.agents());
        assertNull(nullAdmin.health());
        assertNotNull(nullAdmin.auditLog());
    }

    // ── overview() ──

    @Test
    void testOverviewWithNullFramework() {
        var nullAdmin = new AtmosphereAdmin(null, 50);
        var overview = nullAdmin.overview();
        assertEquals("DOWN", overview.get("status"));
        assertNotNull(overview.get("error"));
    }

    @Test
    void testOverviewIncludesAgentCount() {
        var overview = admin.overview();
        assertNotNull(overview);
        assertEquals(0, overview.get("agentCount"));
        assertEquals(0, overview.get("activeSessions"));
    }

    // ── Optional controllers ──

    @Test
    void testOptionalControllersDefaultToNull() {
        assertNull(admin.coordinatorController());
        assertNull(admin.taskController());
        assertNull(admin.aiRuntimeController());
        assertNull(admin.mcpController());
        assertNull(admin.metricsController());
    }

    @Test
    void testSetAndGetCoordinatorController() {
        var ctrl = new Object();
        admin.setCoordinatorController(ctrl);
        assertEquals(ctrl, admin.coordinatorController());
    }

    @Test
    void testSetAndGetTaskController() {
        var ctrl = new Object();
        admin.setTaskController(ctrl);
        assertEquals(ctrl, admin.taskController());
    }

    @Test
    void testSetAndGetAiRuntimeController() {
        var ctrl = new Object();
        admin.setAiRuntimeController(ctrl);
        assertEquals(ctrl, admin.aiRuntimeController());
    }

    @Test
    void testSetAndGetMcpController() {
        var ctrl = new Object();
        admin.setMcpController(ctrl);
        assertEquals(ctrl, admin.mcpController());
    }

    @Test
    void testSetAndGetMetricsController() {
        var ctrl = new Object();
        admin.setMetricsController(ctrl);
        assertEquals(ctrl, admin.metricsController());
    }

    // ── overview with optional controllers ──

    @Test
    @SuppressWarnings("unchecked")
    void testOverviewWithCoordinatorController() {
        // Create a real object with listCoordinators() method via anonymous class
        var coordinator = new Object() {
            @SuppressWarnings("unused")
            public List<Map<String, Object>> listCoordinators() {
                return List.of(Map.of("name", "c1"), Map.of("name", "c2"));
            }
        };
        admin.setCoordinatorController(coordinator);
        var overview = admin.overview();
        assertEquals(2, overview.get("coordinatorCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testOverviewWithTaskController() {
        var taskCtrl = new Object() {
            @SuppressWarnings("unused")
            public List<Map<String, Object>> listTasks(String filter) {
                return List.of(Map.of("id", "t1"));
            }
        };
        admin.setTaskController(taskCtrl);
        var overview = admin.overview();
        assertEquals(1, overview.get("taskCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testOverviewWithAiRuntimeController() {
        var aiCtrl = new Object() {
            @SuppressWarnings("unused")
            public Map<String, Object> getActiveRuntime() {
                return Map.of("name", "openai");
            }
        };
        admin.setAiRuntimeController(aiCtrl);
        var overview = admin.overview();
        assertEquals("openai", overview.get("aiRuntime"));
    }

    @Test
    void testOverviewHandlesReflectionFailureGracefully() {
        // Set a controller without the expected methods
        admin.setCoordinatorController("not-a-real-controller");
        admin.setTaskController("not-a-real-controller");
        admin.setAiRuntimeController("not-a-real-controller");
        var overview = admin.overview();
        // Should not throw, should still have basic fields
        assertNotNull(overview);
        assertEquals(0, overview.get("agentCount"));
        // Should NOT contain coordinator/task/ai keys since reflection failed
        assertNull(overview.get("coordinatorCount"));
        assertNull(overview.get("taskCount"));
        assertNull(overview.get("aiRuntime"));
    }
}
