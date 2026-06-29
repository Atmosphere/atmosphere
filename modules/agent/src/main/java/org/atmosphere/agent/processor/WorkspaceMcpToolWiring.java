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
import org.atmosphere.ai.workspace.WorkspaceExtensions;
import org.atmosphere.mcp.client.McpClientOptions;
import org.atmosphere.mcp.client.McpServerRegistry;
import org.atmosphere.mcp.client.McpToolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Connects the outbound MCP servers declared in {@code MCP.md} and registers
 * their advertised tools into an agent's {@link ToolRegistry}.
 *
 * <p>This class deliberately lives apart from {@code AgentProcessor} and
 * references the optional {@code atmosphere-mcp-client} types only inside its
 * method bodies. {@code AgentProcessor} calls it solely behind a
 * {@link org.atmosphere.agent.ClasspathDetector#hasMcpClient()} guard, so the
 * MCP-client classes are never force-loaded when the module is absent (the same
 * optional-classpath discipline the A2A / channel wiring uses).</p>
 *
 * <p>Connection is best-effort and synchronous at agent-registration time: a
 * server that is down or slow is logged and skipped, never aborting startup.
 * The returned {@link McpServerRegistry} owns the live connections — the caller
 * MUST {@link McpServerRegistry#close() close} it on framework shutdown
 * (Correctness Invariant #1, Ownership / #2, terminal paths).</p>
 *
 * <p><strong>Honest limitations.</strong> Only servers reachable with no
 * per-request credential are wired here — {@link McpClientOptions} carries no
 * auth header, so credential-gated servers (the {@code oauth-delegated} entries
 * the sample {@code MCP.md} documents) are out of scope for this path and must
 * be wired by the application. Tools are listed once at connect time (no live
 * refresh).</p>
 */
final class WorkspaceMcpToolWiring {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceMcpToolWiring.class);

    private WorkspaceMcpToolWiring() {
    }

    /**
     * Connect the declared servers and register their tools.
     *
     * @param agentName    the agent being wired (for logging)
     * @param refs         the parsed {@code MCP.md} server references
     * @param toolRegistry the agent's tool registry to augment
     * @return an {@link AutoCloseable} owning the connected sources (to be
     *         closed on framework shutdown), or {@code null} when nothing
     *         connected
     */
    static AutoCloseable wire(String agentName, List<WorkspaceExtensions.McpServerRef> refs,
                              ToolRegistry toolRegistry) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        var builder = McpServerRegistry.builder();
        var connected = 0;
        for (var ref : refs) {
            try {
                var options = McpClientOptions.builder()
                        .toolNamePrefix(prefixFor(ref.name()))
                        .build();
                builder.add(McpToolSource.connect(ref.uri(), options));
                connected++;
            } catch (RuntimeException e) {
                logger.warn("MCP.md: failed to connect to '{}' ({}) for agent '{}': {}",
                        ref.name(), ref.uri(), agentName, e.getMessage());
            }
        }
        if (connected == 0) {
            return null;
        }
        var registry = builder.build();
        var registered = 0;
        for (var tool : registry.tools()) {
            try {
                toolRegistry.register(tool);
                registered++;
            } catch (RuntimeException e) {
                logger.warn("MCP.md: failed to register remote tool '{}' for agent '{}': {}",
                        tool.name(), agentName, e.getMessage());
            }
        }
        logger.info("MCP.md: connected {} server(s), registered {} remote tool(s) for agent '{}'",
                connected, registered, agentName);
        return registry;
    }

    /**
     * Derive a collision-avoiding, identifier-safe tool-name prefix from the
     * server name (e.g. {@code "weather"} → {@code "weather_"}). Keeps tools
     * from two servers that expose the same tool name distinct.
     */
    private static String prefixFor(String name) {
        var sanitized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? "mcp_" : sanitized + "_";
    }
}
