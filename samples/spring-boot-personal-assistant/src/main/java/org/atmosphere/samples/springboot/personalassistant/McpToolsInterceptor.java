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

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Augments every {@link AiRequest} with the remote MCP server's tools. Runs
 * before the runtime sees the request, so the dispatch path treats the MCP
 * tools identically to locally-declared {@code @AiTool} methods — same
 * approval policy, same observability, same retry semantics. Any
 * {@link org.atmosphere.ai.AgentRuntime} that honors
 * {@link AiRequest#tools()} picks them up; no per-runtime wiring needed.
 */
public class McpToolsInterceptor implements AiInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolsInterceptor.class);

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var source = McpToolSourceHolder.get();
        if (source == null) {
            // RemoteToolsConfig logged the connect failure already — fall
            // through with the original request so the agent still answers
            // (just without remote tools). Don't spam per-request warnings.
            return request;
        }
        var tools = source.tools();
        if (tools.isEmpty()) {
            return request;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Augmenting request with {} remote MCP tool(s) from {}",
                    tools.size(), source.endpoint());
        }
        return request.withTools(tools);
    }
}
