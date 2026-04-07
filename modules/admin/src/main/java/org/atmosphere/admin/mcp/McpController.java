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
package org.atmosphere.admin.mcp;

import org.atmosphere.mcp.registry.McpRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read operations for the MCP registry — tools, resources, and prompts.
 *
 * @since 4.0
 */
public final class McpController {

    private final McpRegistry registry;

    public McpController(McpRegistry registry) {
        this.registry = registry;
    }

    /**
     * List all registered MCP tools.
     */
    public List<Map<String, Object>> listTools() {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : registry.tools().entrySet()) {
            var info = new LinkedHashMap<String, Object>();
            info.put("name", entry.getKey());
            info.put("description", entry.getValue().description());
            var params = new ArrayList<Map<String, Object>>();
            for (var param : entry.getValue().params()) {
                var paramInfo = new LinkedHashMap<String, Object>();
                paramInfo.put("name", param.name());
                paramInfo.put("description", param.description());
                paramInfo.put("required", param.required());
                params.add(paramInfo);
            }
            info.put("params", params);
            result.add(info);
        }
        return result;
    }

    /**
     * List all registered MCP resources.
     */
    public List<Map<String, Object>> listResources() {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : registry.resources().entrySet()) {
            var info = new LinkedHashMap<String, Object>();
            info.put("uri", entry.getKey());
            info.put("name", entry.getValue().name());
            info.put("description", entry.getValue().description());
            info.put("mimeType", entry.getValue().mimeType());
            result.add(info);
        }
        return result;
    }

    /**
     * List all registered MCP prompts.
     */
    public List<Map<String, Object>> listPrompts() {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : registry.prompts().entrySet()) {
            var info = new LinkedHashMap<String, Object>();
            info.put("name", entry.getKey());
            info.put("description", entry.getValue().description());
            result.add(info);
        }
        return result;
    }
}
