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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.mcp.client.McpServerRegistry;
import org.atmosphere.mcp.client.McpToolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Wires the remote MCP server connection at startup. The endpoint URL comes
 * from {@code atmosphere.mcp.client.endpoint}; the resulting
 * {@link McpToolSource} is published as a Spring {@code @Bean} for direct
 * injection into beans like {@link McpClientAdminController}, AND registered
 * into {@link McpToolSourceHolder} for the
 * {@link McpToolsInterceptor} which the {@code @AiEndpoint(interceptors=...)}
 * scanner instantiates reflectively (no Spring DI on the interceptor side).
 *
 * <p>The connection is best-effort: if the upstream MCP server is unreachable
 * at startup, the bean is registered as a closed/empty source and the
 * interceptor falls through (no tools added). This keeps the sample runnable
 * for inspection even when the upstream isn't started.</p>
 */
@Configuration
public class RemoteToolsConfig implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteToolsConfig.class);

    private final String endpoint;
    private McpToolSource source;
    private McpServerRegistry registry;

    public RemoteToolsConfig(@Value("${atmosphere.mcp.client.endpoint:}") String endpoint) {
        this.endpoint = endpoint;
    }

    @Bean
    public McpToolSource mcpToolSource() {
        if (endpoint == null || endpoint.isBlank()) {
            LOG.warn("atmosphere.mcp.client.endpoint not configured — agent will run without remote MCP tools");
            return null;
        }
        try {
            source = McpToolSource.connect(URI.create(endpoint));
            McpToolSourceHolder.set(source);
            LOG.info("Connected to MCP server {} — {} tool(s) advertised",
                    endpoint, source.tools().size());
            return source;
        } catch (RuntimeException ex) {
            // Don't fail startup — the e2e test starts the client agent
            // before fully hitting the upstream, and the runbook needs
            // the agent reachable for diagnostic purposes.
            LOG.warn("Failed to connect to MCP server {} ({}); continuing without remote tools", endpoint, ex.toString());
            return null;
        }
    }

    /**
     * The collision-free aggregation point for remote MCP tools. With a single
     * upstream today it wraps one source; this is where additional
     * {@link McpToolSource} connections (each with its own
     * {@code McpClientOptions} prefix) plug in to expose several servers' tools
     * to one agent without name clashes. Owns the source lifecycle so
     * {@link #destroy()} closes everything through {@link McpServerRegistry#close()}.
     */
    @Bean
    public McpServerRegistry mcpServerRegistry(ObjectProvider<McpToolSource> sources) {
        var builder = McpServerRegistry.builder();
        var src = sources.getIfAvailable();
        if (src != null) {
            builder.add(src);
        }
        registry = builder.build();
        LOG.info("MCP server registry aggregates {} tool(s) across {} source(s)",
                registry.tools().size(), registry.sources().size());
        return registry;
    }

    @Override
    public void destroy() {
        McpToolSourceHolder.clear();
        if (registry != null) {
            registry.close();
        } else if (source != null) {
            source.close();
        }
    }
}
