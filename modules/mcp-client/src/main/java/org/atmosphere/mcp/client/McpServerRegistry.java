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
package org.atmosphere.mcp.client;

import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Aggregates several {@link McpToolSource}s into a single, collision-free tool
 * list an agent can hand to its runtime. Each source has already had its
 * per-server {@link McpClientOptions filtering and renaming} applied; this
 * registry is the last line of defence against name collisions across servers.
 *
 * <p>Collision policy is deterministic <strong>first-wins</strong>: when two
 * servers contribute the same final tool name, the earlier source's tool is
 * kept and the later one is dropped with a warning. Operators avoid the drop
 * entirely by giving each source a distinct {@link McpClientOptions#toolNamePrefix}.</p>
 *
 * <p>Owns the lifecycle of the sources it is given: {@link #close()} closes them
 * all, suppressing individual failures so one bad source can't strand the rest.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (var registry = McpServerRegistry.builder()
 *         .add(McpToolSource.connect(URI.create("http://weather:8080"),
 *                 McpClientOptions.builder().toolNamePrefix("weather_").build()))
 *         .add(McpToolSource.connect(URI.create("http://calendar:8080"),
 *                 McpClientOptions.builder().toolNamePrefix("cal_").build()))
 *         .build()) {
 *     var ctx = AgentExecutionContext.builder().tools(registry.tools()).build();
 * }
 * }</pre>
 */
public final class McpServerRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerRegistry.class);

    private final List<McpToolSource> sources;

    private McpServerRegistry(List<McpToolSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The connected sources, in registration order. */
    public List<McpToolSource> sources() {
        return sources;
    }

    /**
     * The merged, collision-free tool list across all sources, in source
     * registration order.
     */
    public List<ToolDefinition> tools() {
        var perSource = new ArrayList<List<ToolDefinition>>(sources.size());
        for (var source : sources) {
            perSource.add(source.tools());
        }
        return aggregate(perSource);
    }

    /**
     * Merge per-source tool lists with first-wins collision handling. Package
     * private and static so the policy is unit-testable without a live server.
     */
    static List<ToolDefinition> aggregate(List<List<ToolDefinition>> perSource) {
        var merged = new LinkedHashMap<String, ToolDefinition>();
        for (var tools : perSource) {
            if (tools == null) {
                continue;
            }
            for (var tool : tools) {
                var existing = merged.putIfAbsent(tool.name(), tool);
                if (existing != null) {
                    LOG.warn("MCP tool name collision on '{}' — keeping the first source's tool, "
                            + "dropping the duplicate. Prefix the sources to disambiguate.",
                            tool.name());
                }
            }
        }
        return List.copyOf(merged.values());
    }

    @Override
    public void close() {
        for (var source : sources) {
            try {
                source.close();
            } catch (RuntimeException e) {
                LOG.warn("Failed to close MCP source {}: {}", source.endpoint(), e.toString());
            }
        }
    }

    public static final class Builder {
        private final List<McpToolSource> sources = new ArrayList<>();

        public Builder add(McpToolSource source) {
            if (source != null) {
                sources.add(source);
            }
            return this;
        }

        public McpServerRegistry build() {
            return new McpServerRegistry(sources);
        }
    }
}
