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
package org.atmosphere.agent.processor;

import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpHandler;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP-protocol agent registration bridge. Separated from {@link AgentProcessor}
 * so that none of the {@code org.atmosphere.mcp.*} types leak into
 * {@code AgentProcessor}'s bytecode — otherwise samples that pull in
 * {@code atmosphere-agent} without the optional {@code atmosphere-mcp}
 * dependency crash during servlet init with
 * {@code NoClassDefFoundError: org/atmosphere/mcp/registry/McpRegistry$ParamEntry}
 * while the classpath scanner is loading {@code AgentProcessor}'s synthetic
 * lambda methods.
 *
 * <p>This class is loaded reflectively via {@link Class#forName(String)} from
 * {@link AgentProcessor} only after {@link org.atmosphere.agent.ClasspathDetector#hasMcp()}
 * confirms the MCP module is on the classpath, so the mere presence of this
 * class file does not introduce a hard dependency.</p>
 */
final class McpAgentRegistration {

    private static final Logger logger = LoggerFactory.getLogger(McpAgentRegistration.class);

    private McpAgentRegistration() {
    }

    /**
     * Registers an MCP endpoint that exposes every {@code @AiTool} in the
     * {@link ToolRegistry} as an MCP tool, and additionally scans the given
     * {@code instance} for {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt}
     * methods.
     *
     * <p>On success, {@code "mcp"} is appended to {@code protocols}. On any
     * failure a warning is logged and {@code protocols} is left untouched —
     * the caller's other protocol registrations are not affected.</p>
     *
     * @param framework    the Atmosphere framework to add the handler to
     * @param agentName    the agent's logical name (used as MCP server name)
     * @param agentVersion the agent's semantic version
     * @param toolRegistry registry of {@code @AiTool} methods to bridge
     * @param instance     optional agent instance to scan for MCP annotations;
     *                     may be {@code null} in the standard (non-headless) path
     * @param mcpPath      path at which to register the MCP handler
     * @param guardrails   optional guardrails list surfaced in MCP capabilities
     * @param protocols    mutable list to which {@code "mcp"} is added on success
     */
    static void register(AtmosphereFramework framework,
                         String agentName,
                         String agentVersion,
                         ToolRegistry toolRegistry,
                         Object instance,
                         String mcpPath,
                         List<String> guardrails,
                         List<String> protocols) {
        try {
            var mcpRegistry = new McpRegistry();

            // Bridge @AiTool methods (from ToolRegistry) into MCP tools.
            for (var tool : toolRegistry.allTools()) {
                List<McpRegistry.ParamEntry> params = new ArrayList<>();
                for (var p : tool.parameters()) {
                    params.add(new McpRegistry.ParamEntry(
                            p.name(), p.description(), p.required(),
                            jsonSchemaTypeToClass(p.type())));
                }
                var executor = tool.executor();
                mcpRegistry.registerTool(tool.name(), tool.description(),
                        params, (Map<String, Object> args) -> executor.execute(args));
            }

            // Also scan for @McpTool, @McpResource, @McpPrompt directly on the instance.
            if (instance != null) {
                mcpRegistry.scan(instance);
            }

            var protocolHandler = new McpProtocolHandler(
                    agentName,
                    agentVersion,
                    mcpRegistry,
                    framework.getAtmosphereConfig(),
                    guardrails);

            var handler = new McpHandler(protocolHandler);
            framework.addAtmosphereHandler(mcpPath, handler, new ArrayList<>());
            protocols.add("mcp");
            logger.info("MCP endpoint registered at {} with {} tools",
                    mcpPath, mcpRegistry.tools().size());
        } catch (Exception e) {
            logger.warn("Failed to register MCP endpoint for agent '{}': {}",
                    agentName, e.getMessage());
        }
    }

    private static Class<?> jsonSchemaTypeToClass(String type) {
        return switch (type) {
            case "integer" -> int.class;
            case "number" -> double.class;
            case "boolean" -> boolean.class;
            case "object" -> java.util.Map.class;
            case "array" -> java.util.List.class;
            default -> String.class;
        };
    }
}
