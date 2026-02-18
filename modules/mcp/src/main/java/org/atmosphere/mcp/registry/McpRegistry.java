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
package org.atmosphere.mcp.registry;

import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scans an {@code @McpServer} class and builds registries for tools, resources, and prompts.
 */
public final class McpRegistry {

    /**
     * Metadata for a registered MCP tool.
     */
    public record ToolEntry(String name, String description, Method method, Object instance,
                            List<ParamEntry> params) {}

    /**
     * Metadata for a registered MCP resource.
     */
    public record ResourceEntry(String uri, String name, String description, String mimeType,
                                Method method, Object instance, List<ParamEntry> params) {}

    /**
     * Metadata for a registered MCP prompt.
     */
    public record PromptEntry(String name, String description, Method method, Object instance,
                              List<ParamEntry> params) {}

    /**
     * Metadata for a method parameter.
     */
    public record ParamEntry(String name, String description, boolean required, Class<?> type) {}

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final Map<String, ResourceEntry> resources = new LinkedHashMap<>();
    private final Map<String, PromptEntry> prompts = new LinkedHashMap<>();

    /**
     * Scan the given instance for @McpTool, @McpResource, @McpPrompt methods.
     */
    public void scan(Object instance) {
        for (var method : instance.getClass().getMethods()) {
            if (method.isAnnotationPresent(McpTool.class)) {
                var a = method.getAnnotation(McpTool.class);
                var params = extractParams(method);
                tools.put(a.name(), new ToolEntry(a.name(), a.description(), method, instance, params));
            }
            if (method.isAnnotationPresent(McpResource.class)) {
                var a = method.getAnnotation(McpResource.class);
                var params = extractParams(method);
                resources.put(a.uri(), new ResourceEntry(a.uri(), a.name(), a.description(),
                        a.mimeType(), method, instance, params));
            }
            if (method.isAnnotationPresent(McpPrompt.class)) {
                var a = method.getAnnotation(McpPrompt.class);
                var params = extractParams(method);
                prompts.put(a.name(), new PromptEntry(a.name(), a.description(), method, instance, params));
            }
        }
    }

    public Map<String, ToolEntry> tools() {
        return Collections.unmodifiableMap(tools);
    }

    public Map<String, ResourceEntry> resources() {
        return Collections.unmodifiableMap(resources);
    }

    public Map<String, PromptEntry> prompts() {
        return Collections.unmodifiableMap(prompts);
    }

    public Optional<ToolEntry> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Optional<ResourceEntry> resource(String uri) {
        return Optional.ofNullable(resources.get(uri));
    }

    public Optional<PromptEntry> prompt(String name) {
        return Optional.ofNullable(prompts.get(name));
    }

    /**
     * Generate JSON Schema-like input schema for a tool's parameters.
     */
    public static Map<String, Object> inputSchema(ToolEntry tool) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();

        for (var param : tool.params()) {
            var prop = new LinkedHashMap<String, Object>();
            prop.put("type", jsonSchemaType(param.type()));
            if (!param.description().isEmpty()) {
                prop.put("description", param.description());
            }
            properties.put(param.name(), prop);
            if (param.required()) {
                required.add(param.name());
            }
        }

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static List<ParamEntry> extractParams(Method method) {
        var params = new ArrayList<ParamEntry>();
        for (Parameter p : method.getParameters()) {
            var mcpParam = p.getAnnotation(McpParam.class);
            if (mcpParam != null) {
                params.add(new ParamEntry(mcpParam.name(), mcpParam.description(),
                        mcpParam.required(), p.getType()));
            } else {
                // Fall back to parameter name if -parameters compiler flag is used
                params.add(new ParamEntry(p.getName(), "", true, p.getType()));
            }
        }
        return params;
    }

    private static String jsonSchemaType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        return "string";
    }
}
