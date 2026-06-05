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

import io.opentelemetry.api.OpenTelemetry;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.runtime.A2aHandler;
import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.A2aTracing;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.agui.runtime.AgUiHandler;
import org.atmosphere.agui.runtime.AgUiTracing;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpHandler;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpTracing;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link AtmosphereTracingAutoConfiguration}'s {@code attachMcp},
 * {@code attachA2a}, and {@code attachAgUi} helpers walk the framework's registered
 * handlers and install the corresponding protocol tracer. These are the helpers the
 * auto-configuration schedules via a framework {@code startupHook}, so exercising them
 * directly asserts the auto-attach behavior the starter promises.
 */
class AtmosphereTracingAttachTest {

    @Test
    void attachMcpInstallsTracerOnMcpHandler() {
        var framework = new AtmosphereFramework();
        var registry = new McpRegistry();
        var protocolHandler = new McpProtocolHandler("test", "1.0", registry, mock(AtmosphereConfig.class));
        var mcpHandler = new McpHandler(protocolHandler);
        framework.addAtmosphereHandler("/mcp", mcpHandler);

        // Pre-condition: no tracer attached yet.
        assertNull(mcpHandler.protocolHandler().tracing(),
                "MCP protocol handler should start without a tracer");

        AtmosphereTracingAutoConfiguration.attachMcp(framework, new McpTracing(OpenTelemetry.noop()));

        assertNotNull(mcpHandler.protocolHandler().tracing(),
                "attachMcp must install the MCP tracer on the registered McpHandler");
    }

    @Test
    void attachA2aInstallsTracerOnA2aHandler() {
        var framework = new AtmosphereFramework();
        var registry = new A2aRegistry();
        var taskManager = new TaskManager();
        var card = registry.buildAgentCard("test-agent", "Test", "1.0", "/a2a");
        var protocolHandler = new A2aProtocolHandler(registry, taskManager, card);
        var a2aHandler = new A2aHandler(protocolHandler);
        framework.addAtmosphereHandler("/a2a", a2aHandler);

        assertNull(a2aHandler.protocolHandler().tracing(),
                "A2A protocol handler should start without a tracer");

        AtmosphereTracingAutoConfiguration.attachA2a(framework, new A2aTracing(OpenTelemetry.noop()));

        assertNotNull(a2aHandler.protocolHandler().tracing(),
                "attachA2a must install the A2A tracer on the registered A2aHandler");
    }

    @Test
    void attachAgUiInstallsTracerOnAgUiHandler() throws NoSuchMethodException {
        var framework = new AtmosphereFramework();
        var endpoint = new AgUiEndpointStub();
        Method actionMethod = AgUiEndpointStub.class.getDeclaredMethod("action");
        var aguiHandler = new AgUiHandler(endpoint, actionMethod);
        framework.addAtmosphereHandler("/agui", aguiHandler);

        assertNull(aguiHandler.tracing(),
                "AG-UI handler should start without a tracer");

        AtmosphereTracingAutoConfiguration.attachAgUi(framework, new AgUiTracing(OpenTelemetry.noop()));

        assertNotNull(aguiHandler.tracing(),
                "attachAgUi must install the AG-UI tracer on the registered AgUiHandler");
    }

    /** Minimal endpoint whose {@code action} method is used to construct an {@link AgUiHandler}. */
    static final class AgUiEndpointStub {
        public String action() {
            return "ok";
        }
    }
}
