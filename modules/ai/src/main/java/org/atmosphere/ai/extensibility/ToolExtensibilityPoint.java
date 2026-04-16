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
package org.atmosphere.ai.extensibility;

import java.util.List;
import java.util.Objects;

/**
 * Composite primitive for "how agents acquire new capabilities at runtime".
 * Combines the {@link ToolIndex} + {@link DynamicToolSelector} for bounded
 * tool discovery with the {@link McpTrustProvider} for per-user MCP
 * credential resolution.
 *
 * <p>Agent runtimes call {@link #toolsFor(String, int)} to get the subset
 * of registered tools to expose for a given turn, and
 * {@link #mcpCredential(String, String)} when invoking a tool that targets
 * an MCP server registered in the user's {@code MCP.md}.</p>
 *
 * <p>This primitive does NOT own the tool registry itself — tool
 * registration lives in the agent / tool module. The extensibility point
 * adds the layers on top that make large tool catalogs usable and
 * per-user-scoped.</p>
 */
public final class ToolExtensibilityPoint {

    private final ToolIndex toolIndex;
    private final DynamicToolSelector selector;
    private final McpTrustProvider trustProvider;

    public ToolExtensibilityPoint(
            ToolIndex toolIndex,
            DynamicToolSelector selector,
            McpTrustProvider trustProvider) {
        this.toolIndex = Objects.requireNonNull(toolIndex, "toolIndex");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.trustProvider = Objects.requireNonNull(trustProvider, "trustProvider");
    }

    /** The backing tool index. */
    public ToolIndex toolIndex() {
        return toolIndex;
    }

    /** The selector that picks tools per request. */
    public DynamicToolSelector selector() {
        return selector;
    }

    /** The MCP credential trust provider. */
    public McpTrustProvider trustProvider() {
        return trustProvider;
    }

    /**
     * Select up to {@code limit} tools relevant to the query (or to the
     * selector's default limit when {@code limit <= 0}).
     */
    public List<ToolDescriptor> toolsFor(String query, int limit) {
        return limit <= 0
                ? selector.select(query)
                : selector.select(query, limit);
    }

    /** Resolve the credential for a per-user MCP server. */
    public java.util.Optional<String> mcpCredential(String userId, String mcpServerId) {
        return trustProvider.resolve(userId, mcpServerId);
    }
}
