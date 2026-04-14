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
package org.atmosphere.admin.agent;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentControllerTest {

    private AtmosphereFramework framework;
    private AgentController controller;

    @BeforeEach
    public void setUp() {
        framework = mock(AtmosphereFramework.class);
        controller = new AgentController(framework);
    }

    @Test
    public void testListAgentsEmpty() {
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());
        assertTrue(controller.listAgents().isEmpty());
    }

    @Test
    public void testListAgentsDiscoversByPath() {
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/chat-agent", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var agents = controller.listAgents();
        assertEquals(1, agents.size());
        assertEquals("chat-agent", agents.getFirst().get("name"));
        assertEquals("/atmosphere/agent/chat-agent", agents.getFirst().get("path"));
    }

    @Test
    public void testListAgentsSkipsNonAgentPaths() {
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/chat", mockWrapper());
        handlers.put("/atmosphere/admin/events", mockWrapper());
        handlers.put("/atmosphere/agent/my-agent", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var agents = controller.listAgents();
        assertEquals(1, agents.size());
        assertEquals("my-agent", agents.getFirst().get("name"));
    }

    @Test
    public void testListAgentsDetectsProtocols() {
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/smart-agent", mockWrapper());
        handlers.put("/atmosphere/agent/smart-agent/mcp", mockWrapper());
        handlers.put("/atmosphere/agent/smart-agent/a2a", mockWrapper());
        handlers.put("/atmosphere/agent/smart-agent/agui", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var agents = controller.listAgents();
        assertEquals(1, agents.size());

        @SuppressWarnings("unchecked")
        var protocols = (java.util.List<String>) agents.getFirst().get("protocols");
        assertTrue(protocols.contains("a2a"));
        assertTrue(protocols.contains("mcp"));
        assertTrue(protocols.contains("agui"));
    }

    @Test
    public void testListAgentsDeduplicates() {
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/my-agent", mockWrapper());
        handlers.put("/atmosphere/agent/my-agent/mcp", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var agents = controller.listAgents();
        assertEquals(1, agents.size());
    }

    @Test
    public void testListAgentsHeadlessDetection() {
        // Headless agent: has sub-paths but no base path
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/headless-agent/mcp", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var agents = controller.listAgents();
        assertEquals(1, agents.size());
        assertEquals("headless-agent", agents.getFirst().get("name"));
        assertEquals(true, agents.getFirst().get("headless"));
    }

    @Test
    public void testGetAgentFound() {
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/chat-agent", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var result = controller.getAgent("chat-agent");
        assertTrue(result.isPresent());
        assertEquals("chat-agent", result.get().get("name"));
    }

    @Test
    public void testGetAgentNotFound() {
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());
        assertTrue(controller.getAgent("nonexistent").isEmpty());
    }

    @Test
    public void testListSessionsNoRegistry() {
        // AgentSessionRegistry won't be on test classpath → returns empty
        var sessions = controller.listSessions("any-agent");
        assertTrue(sessions.isEmpty());
    }

    @Test
    public void testTotalSessionCountNoRegistry() {
        // AgentSessionRegistry won't be on test classpath → returns 0
        assertEquals(0, controller.totalSessionCount());
    }

    @Test
    public void testMultipleAgents() {
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/agent-a", mockWrapper());
        handlers.put("/atmosphere/agent/agent-b", mockWrapper());
        handlers.put("/atmosphere/agent/agent-c", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var agents = controller.listAgents();
        assertEquals(3, agents.size());
    }

    @Test
    public void testA2aAlternatePathDetection() {
        // A2A can also be at /atmosphere/a2a/{name} (without -agent suffix)
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/weather-agent", mockWrapper());
        handlers.put("/atmosphere/a2a/weather", mockWrapper());
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var agents = controller.listAgents();
        assertEquals(1, agents.size());

        @SuppressWarnings("unchecked")
        var protocols = (java.util.List<String>) agents.getFirst().get("protocols");
        assertTrue(protocols.contains("a2a"));
    }

    private AtmosphereHandlerWrapper mockWrapper() {
        var wrapper = mock(AtmosphereHandlerWrapper.class);
        var handler = mock(AtmosphereHandler.class);
        when(wrapper.atmosphereHandler()).thenReturn(handler);
        return wrapper;
    }
}
