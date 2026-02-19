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
 * Registry for MCP tools, resources, and prompts. Supports both annotation-based
 * discovery (via {@link #scan(Object)}) and programmatic registration
 * (via {@link #registerTool(String, String, List, ToolHandler)}).
 */
public final class McpRegistry {

    /**
     * Functional interface for dynamically registered tools.
     * Receives a map of argument name → value, returns the tool result.
     */
    @FunctionalInterface
    public interface ToolHandler {
        Object execute(Map<String, Object> arguments) throws Exception;
    }

    /**
     * Functional interface for dynamically registered resources.
     * Receives a map of argument name → value, returns the resource content.
     */
    @FunctionalInterface
    public interface ResourceHandler {
        Object read(Map<String, Object> arguments) throws Exception;
    }

    /**
     * Functional interface for dynamically registered prompts.
     * Receives a map of argument name → value, returns prompt messages.
     */
    @FunctionalInterface
    public interface PromptHandler {
        Object get(Map<String, Object> arguments) throws Exception;
    }

    /**
     * Metadata for a registered MCP tool.
     */
    public record ToolEntry(String name, String description, Method method, Object instance,
                            List<ParamEntry> params, ToolHandler handler) {
        /** Annotation-based constructor (method + instance). */
        public ToolEntry(String name, String description, Method method, Object instance,
                         List<ParamEntry> params) {
            this(name, description, method, instance, params, null);
        }

        /** Programmatic constructor (handler lambda). */
        public ToolEntry(String name, String description, List<ParamEntry> params,
                         ToolHandler handler) {
            this(name, description, null, null, params, handler);
        }

        /** Returns true if this tool uses a programmatic handler. */
        public boolean isDynamic() {
            return handler != null;
        }
    }

    /**
     * Metadata for a registered MCP resource.
     */
    public record ResourceEntry(String uri, String name, String description, String mimeType,
                                Method method, Object instance, List<ParamEntry> params,
                                ResourceHandler handler) {
        /** Annotation-based constructor. */
        public ResourceEntry(String uri, String name, String description, String mimeType,
                             Method method, Object instance, List<ParamEntry> params) {
            this(uri, name, description, mimeType, method, instance, params, null);
        }

        /** Programmatic constructor. */
        public ResourceEntry(String uri, String name, String description, String mimeType,
                             List<ParamEntry> params, ResourceHandler handler) {
            this(uri, name, description, mimeType, null, null, params, handler);
        }

        public boolean isDynamic() {
            return handler != null;
        }
    }

    /**
     * Metadata for a registered MCP prompt.
     */
    public record PromptEntry(String name, String description, Method method, Object instance,
                              List<ParamEntry> params, PromptHandler handler) {
        /** Annotation-based constructor. */
        public PromptEntry(String name, String description, Method method, Object instance,
                           List<ParamEntry> params) {
            this(name, description, method, instance, params, null);
        }

        /** Programmatic constructor. */
        public PromptEntry(String name, String description, List<ParamEntry> params,
                           PromptHandler handler) {
            this(name, description, null, null, params, handler);
        }

        public boolean isDynamic() {
            return handler != null;
        }
    }

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

    // ── Programmatic Tool Registration ───────────────────────────────────

    /**
     * Register a tool with a lambda handler.
     *
     * @param name        tool name (unique identifier)
     * @param description human-readable description for AI agents
     * @param params      parameter metadata (name, description, required, type)
     * @param handler     function that receives arguments and returns the result
     */
    public void registerTool(String name, String description, List<ParamEntry> params,
                             ToolHandler handler) {
        tools.put(name, new ToolEntry(name, description, params, handler));
    }

    /**
     * Register a tool with no parameters.
     */
    public void registerTool(String name, String description, ToolHandler handler) {
        registerTool(name, description, List.of(), handler);
    }

    /**
     * Remove a previously registered tool.
     *
     * @return true if the tool was found and removed
     */
    public boolean removeTool(String name) {
        return tools.remove(name) != null;
    }

    // ── Programmatic Resource Registration ───────────────────────────────

    /**
     * Register a resource with a lambda handler.
     */
    public void registerResource(String uri, String name, String description,
                                 String mimeType, List<ParamEntry> params,
                                 ResourceHandler handler) {
        resources.put(uri, new ResourceEntry(uri, name, description, mimeType, params, handler));
    }

    /**
     * Register a resource with no parameters.
     */
    public void registerResource(String uri, String name, String description,
                                 String mimeType, ResourceHandler handler) {
        registerResource(uri, name, description, mimeType, List.of(), handler);
    }

    /**
     * Remove a previously registered resource.
     */
    public boolean removeResource(String uri) {
        return resources.remove(uri) != null;
    }

    // ── Programmatic Prompt Registration ─────────────────────────────────

    /**
     * Register a prompt with a lambda handler.
     */
    public void registerPrompt(String name, String description, List<ParamEntry> params,
                               PromptHandler handler) {
        prompts.put(name, new PromptEntry(name, description, params, handler));
    }

    /**
     * Register a prompt with no parameters.
     */
    public void registerPrompt(String name, String description, PromptHandler handler) {
        registerPrompt(name, description, List.of(), handler);
    }

    /**
     * Remove a previously registered prompt.
     */
    public boolean removePrompt(String name) {
        return prompts.remove(name) != null;
    }

    // ── Queries ──────────────────────────────────────────────────────────

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
