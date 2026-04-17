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
package org.atmosphere.coordinator.bridge;

import org.atmosphere.ai.bridge.ProtocolBridge;
import org.atmosphere.cpr.AtmosphereFramework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * First-class in-JVM {@link ProtocolBridge} for agent dispatch. Reports
 * every {@code @Agent} registered at a path under
 * {@code /atmosphere/agent/} as reachable through this bridge. The
 * {@code @Coordinator} fleet orchestration routes local crew members
 * through this bridge, putting local dispatch on equal architectural
 * footing with wire bridges (MCP, A2A, AG-UI, gRPC) — the Atmosphere 1.0
 * Broadcaster pattern applied to agent dispatch.
 *
 * <p>This bridge does not itself own the per-agent
 * {@link org.atmosphere.coordinator.transport.LocalAgentTransport}
 * machinery; it surfaces the in-JVM protocol as a named, discoverable
 * primitive on the admin control plane and captures the architectural
 * intent that in-JVM is a protocol, not a special case.</p>
 */
public final class InMemoryProtocolBridge implements ProtocolBridge {

    public static final String NAME = "in-memory";

    private static final String AGENT_PATH_PREFIX = "/atmosphere/agent/";

    // Paths the InMemory bridge considers "terminal agent handlers" — a path
    // like /atmosphere/agent/pierre counts, but /atmosphere/agent/pierre/mcp
    // belongs to the McpProtocolBridge.
    private static final Set<String> WIRE_SUB_PATHS =
            Set.of("mcp", "a2a", "agui", "grpc");

    private final AtmosphereFramework framework;

    public InMemoryProtocolBridge(AtmosphereFramework framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Kind kind() {
        return Kind.IN_JVM;
    }

    @Override
    public boolean isActive() {
        return framework != null && framework.initialized();
    }

    @Override
    public String describe() {
        return "in-JVM agent dispatch via AtmosphereFramework handler map";
    }

    @Override
    public List<String> agentPaths() {
        if (!isActive()) {
            return List.of();
        }
        var handlers = framework.getAtmosphereHandlers();
        var paths = new ArrayList<String>();
        for (var key : handlers.keySet()) {
            if (!key.startsWith(AGENT_PATH_PREFIX)) {
                continue;
            }
            var suffix = key.substring(AGENT_PATH_PREFIX.length());
            if (suffix.isEmpty()) {
                continue;
            }
            // Skip wire sub-paths (they belong to the corresponding wire
            // bridges). A path like /atmosphere/agent/pierre/mcp is surfaced
            // by McpProtocolBridge, not this one.
            var slash = suffix.indexOf('/');
            if (slash >= 0) {
                var tail = suffix.substring(slash + 1);
                if (WIRE_SUB_PATHS.contains(tail)) {
                    continue;
                }
            }
            paths.add(key);
        }
        return List.copyOf(paths);
    }

    @Override
    public int order() {
        return 0; // Runs first in admin listings.
    }
}
