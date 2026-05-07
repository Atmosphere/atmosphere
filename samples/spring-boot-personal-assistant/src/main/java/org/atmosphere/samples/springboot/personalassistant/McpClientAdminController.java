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

import org.atmosphere.mcp.client.McpToolSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator-grade visibility into the outbound-MCP wiring. Surfaces the
 * remote source's connection state, advertised tool inventory, and per-tool
 * dispatch metrics (call count, error count, last/avg latency).
 *
 * <p>Lives in the sample rather than the {@code atmosphere-mcp-client}
 * module so the module stays lean (a single SPI + executor); production
 * deployments that want full {@code /atmosphere/admin/mcp-client}
 * integration can promote this controller's logic into a custom controller
 * registered with {@code AtmosphereAdmin.setMcpClientController(...)}.</p>
 *
 * <p>Returns:</p>
 * <pre>{@code
 * GET /api/mcp-client/sources
 * {
 *   "connected": true,
 *   "endpoint": "http://localhost:8083/atmosphere/mcp",
 *   "toolCount": 5,
 *   "tools": [
 *     {"name": "list_users", "calls": 2, "errors": 1, "lastLatencyMs": 14, "avgLatencyMs": 12},
 *     ...
 *   ]
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/mcp-client")
public class McpClientAdminController {

    private final McpToolSource source;

    @Autowired
    public McpClientAdminController(@Autowired(required = false) McpToolSource source) {
        // required=false: when the upstream is unreachable at startup,
        // RemoteToolsConfig.mcpToolSource() returns null and Spring leaves
        // this dependency unfulfilled. The controller still mounts so the
        // operator can confirm the endpoint exists and see the disconnected
        // state.
        this.source = source;
    }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        var out = new LinkedHashMap<String, Object>();
        if (source == null) {
            out.put("connected", false);
            out.put("endpoint", null);
            out.put("toolCount", 0);
            out.put("tools", List.of());
            return out;
        }
        out.put("connected", true);
        out.put("endpoint", source.endpoint());
        out.put("toolCount", source.tools().size());
        var rows = new java.util.ArrayList<Map<String, Object>>(source.tools().size());
        for (var def : source.tools()) {
            var metrics = source.metrics().get(def.name());
            var row = new LinkedHashMap<String, Object>();
            row.put("name", def.name());
            row.put("description", def.description());
            row.put("calls", metrics == null ? 0L : metrics.calls());
            row.put("errors", metrics == null ? 0L : metrics.errors());
            row.put("lastLatencyMs", metrics == null ? 0L : metrics.lastLatencyMs());
            row.put("avgLatencyMs", metrics == null ? 0L : metrics.avgLatencyMs());
            rows.add(row);
        }
        out.put("tools", rows);
        return out;
    }
}
