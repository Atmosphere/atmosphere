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
package org.atmosphere.mcp.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.mcp.protocol.JsonRpc;
import org.atmosphere.mcp.protocol.McpMethod;
import org.atmosphere.mcp.registry.McpRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core MCP protocol handler. Processes incoming JSON-RPC messages and dispatches
 * to the appropriate MCP method handler (initialize, tools/list, tools/call, etc.).
 */
public final class McpProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(McpProtocolHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String serverName;
    private final String serverVersion;
    private final McpRegistry registry;

    public McpProtocolHandler(String serverName, String serverVersion, McpRegistry registry) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.registry = registry;
    }

    /**
     * Handle an incoming message from an MCP client.
     * Returns the JSON-RPC response to send back, or null for notifications.
     */
    public String handleMessage(AtmosphereResource resource, String message) {
        try {
            var node = mapper.readTree(message);
            var method = node.has("method") ? node.get("method").asText() : null;
            var id = node.has("id") ? node.get("id") : null;

            if (method == null) {
                return serialize(JsonRpc.Response.error(idValue(id),
                        JsonRpc.INVALID_REQUEST, "Missing method"));
            }

            // Notifications (no id) — don't send a response
            if (id == null || id.isNull()) {
                handleNotification(resource, method, node.get("params"));
                return null;
            }

            var idVal = idValue(id);
            return serialize(switch (method) {
                case McpMethod.INITIALIZE -> handleInitialize(resource, idVal, node.get("params"));
                case McpMethod.PING -> JsonRpc.Response.success(idVal, Map.of());
                case McpMethod.TOOLS_LIST -> handleToolsList(idVal);
                case McpMethod.TOOLS_CALL -> handleToolsCall(idVal, node.get("params"));
                case McpMethod.RESOURCES_LIST -> handleResourcesList(idVal);
                case McpMethod.RESOURCES_READ -> handleResourcesRead(idVal, node.get("params"));
                case McpMethod.PROMPTS_LIST -> handlePromptsList(idVal);
                case McpMethod.PROMPTS_GET -> handlePromptsGet(idVal, node.get("params"));
                default -> JsonRpc.Response.error(idVal, JsonRpc.METHOD_NOT_FOUND,
                        "Unknown method: " + method);
            });
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse JSON-RPC message", e);
            return serialize(JsonRpc.Response.error(null, JsonRpc.PARSE_ERROR,
                    "Invalid JSON: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Error handling MCP message", e);
            return serialize(JsonRpc.Response.error(null, JsonRpc.INTERNAL_ERROR,
                    e.getMessage()));
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    private JsonRpc.Response handleInitialize(AtmosphereResource resource, Object id, JsonNode params) {
        var session = getOrCreateSession(resource);

        if (params != null) {
            var clientInfo = params.get("clientInfo");
            var capabilities = params.get("capabilities");
            session.setClientInfo(
                    clientInfo != null && clientInfo.has("name") ? clientInfo.get("name").asText() : "unknown",
                    clientInfo != null && clientInfo.has("version") ? clientInfo.get("version").asText() : "unknown",
                    capabilities != null ? mapper.convertValue(capabilities, Map.class) : Map.of()
            );
        }

        var serverCapabilities = new LinkedHashMap<String, Object>();
        if (!registry.tools().isEmpty()) {
            serverCapabilities.put("tools", Map.of("listChanged", true));
        }
        if (!registry.resources().isEmpty()) {
            serverCapabilities.put("resources", Map.of("subscribe", true, "listChanged", true));
        }
        if (!registry.prompts().isEmpty()) {
            serverCapabilities.put("prompts", Map.of("listChanged", true));
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", "2025-03-26");
        result.put("capabilities", serverCapabilities);
        result.put("serverInfo", Map.of("name", serverName, "version", serverVersion));

        logger.info("MCP client initialized: {} v{}", session.clientName(), session.clientVersion());
        return JsonRpc.Response.success(id, result);
    }

    private void handleNotification(AtmosphereResource resource, String method, JsonNode params) {
        if (McpMethod.INITIALIZED.equals(method)) {
            var session = getOrCreateSession(resource);
            session.markInitialized();
            logger.debug("MCP session fully initialized for resource {}", resource.uuid());
        }
    }

    // ── Tools ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleToolsList(Object id) {
        var toolList = new ArrayList<Map<String, Object>>();
        for (var entry : registry.tools().values()) {
            var tool = new LinkedHashMap<String, Object>();
            tool.put("name", entry.name());
            if (!entry.description().isEmpty()) {
                tool.put("description", entry.description());
            }
            tool.put("inputSchema", McpRegistry.inputSchema(entry));
            toolList.add(tool);
        }
        return JsonRpc.Response.success(id, Map.of("tools", toolList));
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleToolsCall(Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing tool name");
        }
        var toolName = params.get("name").asText();
        var toolOpt = registry.tool(toolName);
        if (toolOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown tool: " + toolName);
        }

        var tool = toolOpt.get();
        var arguments = params.has("arguments") ? params.get("arguments") : null;

        try {
            Object result;
            if (tool.isDynamic()) {
                // Lambda-based tool — pass arguments as Map<String, Object>
                var argMap = bindArgumentsAsMap(tool.params(), arguments);
                result = tool.handler().execute(argMap);
            } else {
                // Annotation-based tool — invoke via reflection
                var args = bindArguments(tool.method(), tool.params(), arguments);
                result = tool.method().invoke(tool.instance(), args);
            }
            var text = result instanceof String s ? s : mapper.writeValueAsString(result);

            return JsonRpc.Response.success(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", text)),
                    "isError", false
            ));
        } catch (InvocationTargetException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Tool {} invocation failed", toolName, cause);
            return JsonRpc.Response.success(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", cause.getMessage())),
                    "isError", true
            ));
        } catch (Exception e) {
            logger.warn("Tool {} invocation failed", toolName, e);
            return JsonRpc.Response.error(id, JsonRpc.INTERNAL_ERROR,
                    "Tool invocation failed: " + e.getMessage());
        }
    }

    // ── Resources ────────────────────────────────────────────────────────

    private JsonRpc.Response handleResourcesList(Object id) {
        var resourceList = new ArrayList<Map<String, Object>>();
        for (var entry : registry.resources().values()) {
            var res = new LinkedHashMap<String, Object>();
            res.put("uri", entry.uri());
            if (!entry.name().isEmpty()) {
                res.put("name", entry.name());
            }
            if (!entry.description().isEmpty()) {
                res.put("description", entry.description());
            }
            res.put("mimeType", entry.mimeType());
            resourceList.add(res);
        }
        return JsonRpc.Response.success(id, Map.of("resources", resourceList));
    }

    private JsonRpc.Response handleResourcesRead(Object id, JsonNode params) {
        if (params == null || !params.has("uri")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing resource URI");
        }
        var uri = params.get("uri").asText();
        var resOpt = registry.resource(uri);
        if (resOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown resource: " + uri);
        }

        var res = resOpt.get();
        try {
            Object result;
            if (res.isDynamic()) {
                var argMap = bindArgumentsAsMap(res.params(),
                        params.has("arguments") ? params.get("arguments") : null);
                result = res.handler().read(argMap);
            } else {
                var args = bindArguments(res.method(), res.params(),
                        params.has("arguments") ? params.get("arguments") : null);
                result = res.method().invoke(res.instance(), args);
            }
            var text = result instanceof String s ? s : mapper.writeValueAsString(result);

            return JsonRpc.Response.success(id, Map.of(
                    "contents", List.of(Map.of(
                            "uri", uri,
                            "mimeType", res.mimeType(),
                            "text", text
                    ))
            ));
        } catch (Exception e) {
            logger.warn("Resource {} read failed", uri, e);
            return JsonRpc.Response.error(id, JsonRpc.INTERNAL_ERROR,
                    "Resource read failed: " + e.getMessage());
        }
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    private JsonRpc.Response handlePromptsList(Object id) {
        var promptList = new ArrayList<Map<String, Object>>();
        for (var entry : registry.prompts().values()) {
            var prompt = new LinkedHashMap<String, Object>();
            prompt.put("name", entry.name());
            if (!entry.description().isEmpty()) {
                prompt.put("description", entry.description());
            }
            var args = new ArrayList<Map<String, Object>>();
            for (var param : entry.params()) {
                var arg = new LinkedHashMap<String, Object>();
                arg.put("name", param.name());
                if (!param.description().isEmpty()) {
                    arg.put("description", param.description());
                }
                arg.put("required", param.required());
                args.add(arg);
            }
            if (!args.isEmpty()) {
                prompt.put("arguments", args);
            }
            promptList.add(prompt);
        }
        return JsonRpc.Response.success(id, Map.of("prompts", promptList));
    }

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handlePromptsGet(Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing prompt name");
        }
        var promptName = params.get("name").asText();
        var promptOpt = registry.prompt(promptName);
        if (promptOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown prompt: " + promptName);
        }

        var prompt = promptOpt.get();
        try {
            var arguments = params.has("arguments") ? params.get("arguments") : null;
            Object result;
            if (prompt.isDynamic()) {
                var argMap = bindArgumentsAsMap(prompt.params(), arguments);
                result = prompt.handler().get(argMap);
            } else {
                var args = bindArguments(prompt.method(), prompt.params(), arguments);
                result = prompt.method().invoke(prompt.instance(), args);
            }

            // Result should be a List of maps with "role" and "content"
            Object messages;
            if (result instanceof List<?> list) {
                messages = list;
            } else {
                messages = List.of(Map.of("role", "user",
                        "content", Map.of("type", "text", "text", String.valueOf(result))));
            }

            return JsonRpc.Response.success(id, Map.of(
                    "description", prompt.description(),
                    "messages", messages
            ));
        } catch (Exception e) {
            logger.warn("Prompt {} get failed", promptName, e);
            return JsonRpc.Response.error(id, JsonRpc.INTERNAL_ERROR,
                    "Prompt get failed: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Object[] bindArguments(java.lang.reflect.Method method,
                                   List<McpRegistry.ParamEntry> paramEntries,
                                   JsonNode arguments) {
        if (paramEntries.isEmpty()) {
            return new Object[0];
        }
        var args = new Object[paramEntries.size()];
        for (int i = 0; i < paramEntries.size(); i++) {
            var param = paramEntries.get(i);
            if (arguments != null && arguments.has(param.name())) {
                args[i] = convertParam(arguments.get(param.name()), param.type());
            } else {
                args[i] = defaultValue(param.type());
            }
        }
        return args;
    }

    /**
     * Bind JSON arguments to a Map for dynamic (lambda-based) handlers.
     */
    private Map<String, Object> bindArgumentsAsMap(List<McpRegistry.ParamEntry> paramEntries,
                                                   JsonNode arguments) {
        var map = new LinkedHashMap<String, Object>();
        for (var param : paramEntries) {
            if (arguments != null && arguments.has(param.name())) {
                map.put(param.name(), convertParam(arguments.get(param.name()), param.type()));
            } else {
                map.put(param.name(), defaultValue(param.type()));
            }
        }
        // Also include any extra arguments not declared in params
        if (arguments != null) {
            var it = arguments.fields();
            while (it.hasNext()) {
                var field = it.next();
                map.putIfAbsent(field.getKey(), field.getValue().asText());
            }
        }
        return map;
    }

    private Object convertParam(JsonNode node, Class<?> type) {
        if (node == null || node.isNull()) return defaultValue(type);
        if (type == String.class) return node.asText();
        if (type == int.class || type == Integer.class) return node.asInt();
        if (type == long.class || type == Long.class) return node.asLong();
        if (type == double.class || type == Double.class) return node.asDouble();
        if (type == float.class || type == Float.class) return (float) node.asDouble();
        if (type == boolean.class || type == Boolean.class) return node.asBoolean();
        // Complex types — try Jackson deserialization
        return mapper.convertValue(node, type);
    }

    private Object defaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        return null;
    }

    private Object idValue(JsonNode id) {
        if (id == null || id.isNull()) return null;
        if (id.isNumber()) return id.numberValue();
        return id.asText();
    }

    private McpSession getOrCreateSession(AtmosphereResource resource) {
        var session = (McpSession) resource.getRequest().getAttribute(McpSession.ATTRIBUTE_KEY);
        if (session == null) {
            session = new McpSession();
            resource.getRequest().setAttribute(McpSession.ATTRIBUTE_KEY, session);
        }
        return session;
    }

    private String serialize(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize JSON-RPC response", e);
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Serialization failed\"}}";
        }
    }
}
