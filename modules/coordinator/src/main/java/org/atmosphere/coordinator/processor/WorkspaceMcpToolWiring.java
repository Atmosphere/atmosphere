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
package org.atmosphere.coordinator.processor;

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
 * Connects the outbound MCP servers declared in a {@code @Coordinator}'s
 * {@code MCP.md} and registers their advertised tools into the coordinator's
 * {@link ToolRegistry}. Mirrors the {@code AgentProcessor} helper of the same
 * name (Correctness Invariant #7, Mode Parity); it references the optional
 * {@code atmosphere-mcp-client} types only inside its method bodies and is
 * called solely behind a {@code hasMcpClient()} guard.
 *
 * <p>Connection is best-effort and synchronous at registration time; the
 * returned {@link McpServerRegistry} owns the live connections and MUST be
 * closed on framework shutdown (Correctness Invariant #1, Ownership).</p>
 */
final class WorkspaceMcpToolWiring {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceMcpToolWiring.class);

    private WorkspaceMcpToolWiring() {
    }

    static AutoCloseable wire(String coordinatorName, List<WorkspaceExtensions.McpServerRef> refs,
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
                logger.warn("MCP.md: failed to connect to '{}' ({}) for coordinator '{}': {}",
                        ref.name(), ref.uri(), coordinatorName, e.getMessage());
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
                logger.warn("MCP.md: failed to register remote tool '{}' for coordinator '{}': {}",
                        tool.name(), coordinatorName, e.getMessage());
            }
        }
        logger.info("MCP.md: connected {} server(s), registered {} remote tool(s) for coordinator '{}'",
                connected, registered, coordinatorName);
        return registry;
    }

    private static String prefixFor(String name) {
        var sanitized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? "mcp_" : sanitized + "_";
    }
}
