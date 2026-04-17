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
package org.atmosphere.mcp.bridge;

import org.atmosphere.ai.bridge.ProtocolBridge;
import org.atmosphere.cpr.AtmosphereFramework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link ProtocolBridge} implementation that enumerates agents reachable
 * via MCP (Model Context Protocol). Wraps the existing MCP handler
 * infrastructure; actual request routing continues through
 * {@code McpHandler} / {@code McpProtocolHandler} — the bridge surfaces
 * the protocol as a named, discoverable primitive so admin tooling can
 * answer "which agents are reachable via MCP right now?" consistently
 * across in-memory, MCP, A2A, AG-UI, and gRPC.
 */
public final class McpProtocolBridge implements ProtocolBridge {

    public static final String NAME = "mcp";

    private static final String AGENT_PATH_PREFIX = "/atmosphere/agent/";
    private static final String MCP_SUFFIX = "/mcp";

    private final AtmosphereFramework framework;

    public McpProtocolBridge(AtmosphereFramework framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Kind kind() {
        return Kind.WIRE;
    }

    @Override
    public boolean isActive() {
        return framework != null && framework.initialized() && !agentPaths().isEmpty();
    }

    @Override
    public String describe() {
        return "MCP (Model Context Protocol) — agents exposed at /atmosphere/agent/{name}/mcp";
    }

    @Override
    public List<String> agentPaths() {
        if (framework == null || !framework.initialized()) {
            return List.of();
        }
        var paths = new ArrayList<String>();
        for (var key : framework.getAtmosphereHandlers().keySet()) {
            if (key.startsWith(AGENT_PATH_PREFIX) && key.endsWith(MCP_SUFFIX)) {
                paths.add(key);
            }
        }
        return List.copyOf(paths);
    }

    @Override
    public int order() {
        return 20;
    }
}
