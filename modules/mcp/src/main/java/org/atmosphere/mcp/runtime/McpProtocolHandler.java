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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.mcp.protocol.JsonRpc;
import org.atmosphere.mcp.protocol.McpMethod;
import org.atmosphere.mcp.registry.McpRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Core MCP protocol handler. Processes incoming JSON-RPC messages and dispatches
 * to the appropriate MCP method handler (initialize, tools/list, tools/call, etc.).
 */
public final class McpProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(McpProtocolHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * The latest MCP spec revision this server speaks.
     * <p>Bumped to {@code 2025-11-25} (icons, EnumSchema, URL-mode elicitation,
     * standards-based ElicitResult, OIDC discovery hooks, JSON Schema 2020-12
     * default dialect). Version negotiation: if the client requests a revision
     * we know about ({@link #SUPPORTED_VERSIONS}), we respond with theirs;
     * otherwise we respond with the latest we speak and let the client decide
     * whether to proceed.</p>
     */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /**
     * Revisions this server understands, newest first. Used during the
     * initialize handshake to honor clients pinned to older specs.
     */
    public static final List<String> SUPPORTED_VERSIONS = List.of(
            "2025-11-25",
            "2025-06-18",
            "2025-03-26",
            "2024-11-05"
    );

    private final String serverName;
    private final String serverVersion;
    private final McpRegistry registry;
    private final AtmosphereConfig config;
    private final List<String> guardrails;
    private final McpTaskManager taskManager = new McpTaskManager();
    private volatile McpTracing tracing;

    public McpProtocolHandler(String serverName, String serverVersion,
                              McpRegistry registry, AtmosphereConfig config) {
        this(serverName, serverVersion, registry, config, List.of());
    }

    public McpProtocolHandler(String serverName, String serverVersion,
                              McpRegistry registry, AtmosphereConfig config,
                              List<String> guardrails) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.registry = registry;
        this.config = config;
        this.guardrails = guardrails != null ? List.copyOf(guardrails) : List.of();
    }

    /**
     * Set the optional MCP tracing instance for OpenTelemetry instrumentation.
     */
    public void setTracing(McpTracing tracing) {
        this.tracing = tracing;
    }

    /**
     * Handle an incoming message from an MCP client.
     * Returns the JSON-RPC response to send back, or null for notifications.
     */
    public String handleMessage(AtmosphereResource resource, String message) {
        try {
            var node = mapper.readTree(message);

            // MCP 2025-06-18 dropped JSON-RPC batching. Reject batches outright
            // with INVALID_REQUEST so a non-conforming client gets a clear
            // diagnostic instead of partial-success ambiguity.
            if (node.isArray()) {
                return serialize(JsonRpc.Response.error(null, JsonRpc.INVALID_REQUEST,
                        "JSON-RPC batching is not supported (removed in MCP 2025-06-18)"));
            }

            return handleSingleMessage(resource, node);
        } catch (JacksonException e) {
            logger.warn("Failed to parse JSON-RPC message", e);
            return serialize(JsonRpc.Response.error(null, JsonRpc.PARSE_ERROR,
                    "Invalid JSON: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Error handling MCP message", e);
            return serialize(JsonRpc.Response.error(null, JsonRpc.INTERNAL_ERROR,
                    e.getMessage()));
        }
    }

    private String handleSingleMessage(AtmosphereResource resource, JsonNode node) {
        var method = node.has("method") ? node.get("method").stringValue() : null;
        var id = node.has("id") ? node.get("id") : null;

        // Response envelope (no method, has id, has result|error) — this is
        // a client reply to a server-initiated request such as
        // elicitation/create. Route it back to the waiting future on the
        // session and return null (no further response from the server).
        if (method == null && id != null && !id.isNull()
                && (node.has("result") || node.has("error"))) {
            var session = (McpSession) resource.getRequest()
                    .getAttribute(McpSession.ATTRIBUTE_KEY);
            if (session != null) {
                var requestId = id.isString() ? id.stringValue() : id.toString();
                if (!session.completeServerRequest(requestId, node)) {
                    logger.debug("Received response for unknown server request id={} on session {}",
                            requestId, session.sessionId());
                }
            }
            return null;
        }

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
            case McpMethod.TOOLS_CALL -> handleToolsCall(resource, idVal, node.get("params"));
            case McpMethod.RESOURCES_LIST -> handleResourcesList(idVal);
            case McpMethod.RESOURCES_READ -> handleResourcesRead(idVal, node.get("params"));
            case McpMethod.RESOURCES_SUBSCRIBE -> handleResourcesSubscribe(resource, idVal, node.get("params"));
            case McpMethod.RESOURCES_UNSUBSCRIBE -> handleResourcesUnsubscribe(resource, idVal, node.get("params"));
            case McpMethod.PROMPTS_LIST -> handlePromptsList(idVal);
            case McpMethod.PROMPTS_GET -> handlePromptsGet(idVal, node.get("params"));
            case McpMethod.TASKS_GET -> handleTasksGet(idVal, node.get("params"));
            case McpMethod.TASKS_RESULT -> handleTasksResult(idVal, node.get("params"));
            case McpMethod.TASKS_LIST -> handleTasksList(idVal, node.get("params"));
            case McpMethod.TASKS_CANCEL -> handleTasksCancel(idVal, node.get("params"));
            default -> JsonRpc.Response.error(idVal, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown method: " + method);
        });
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JsonRpc.Response handleInitialize(AtmosphereResource resource, Object id, JsonNode params) {
        var session = getOrCreateSession(resource);

        // Honor the version the client asked for if we know it; otherwise
        // fall back to our latest. Clients that don't recognize the response
        // version are expected to disconnect (per spec).
        String negotiatedVersion = PROTOCOL_VERSION;
        if (params != null) {
            var clientInfo = params.get("clientInfo");
            var capabilities = params.get("capabilities");
            var requested = params.get("protocolVersion");
            if (requested != null && requested.isString()) {
                var asked = requested.stringValue();
                if (SUPPORTED_VERSIONS.contains(asked)) {
                    negotiatedVersion = asked;
                }
            }
            session.setClientInfo(
                    clientInfo != null && clientInfo.has("name") ? clientInfo.get("name").stringValue() : "unknown",
                    clientInfo != null && clientInfo.has("version") ? clientInfo.get("version").stringValue() : "unknown",
                    capabilities != null ? mapper.convertValue(capabilities, Map.class) : Map.of()
            );
            session.setProtocolVersion(negotiatedVersion);
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
        // MCP 2025-11-25 (experimental): advertise task support for the
        // request types we accept task-augmentation on. Tools/call is the
        // only one we support today; sampling/elicitation augmentation is
        // a future iteration.
        serverCapabilities.put("tasks", Map.of(
                "list", Map.of(),
                "cancel", Map.of(),
                "requests", Map.of("tools", Map.of("call", Map.of()))
        ));

        var serverInfo = new LinkedHashMap<String, Object>();
        serverInfo.put("name", serverName);
        serverInfo.put("version", serverVersion);
        if (!guardrails.isEmpty()) {
            serverInfo.put("guardrails", guardrails);
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", negotiatedVersion);
        result.put("capabilities", serverCapabilities);
        result.put("serverInfo", serverInfo);

        logger.info("MCP client initialized: {} v{}", session.clientName(), session.clientVersion());
        return JsonRpc.Response.success(id, result);
    }

    private void handleNotification(AtmosphereResource resource, String method, JsonNode params) {
        switch (method) {
            case McpMethod.INITIALIZED -> {
                var session = getOrCreateSession(resource);
                session.markInitialized();
                logger.debug("MCP session fully initialized for resource {}", resource.uuid());
            }
            case McpMethod.CANCELLED -> {
                var requestId = params != null && params.has("requestId")
                        ? params.get("requestId").stringValue() : "unknown";
                logger.debug("MCP client cancelled request {} for resource {}",
                        requestId, resource.uuid());
            }
            default -> logger.trace("Ignoring unknown notification: {}", method);
        }
    }

    // ── Tools ────────────────────────────────────────────────────────────

    private JsonRpc.Response handleToolsList(Object id) {
        var toolList = new ArrayList<Map<String, Object>>();
        for (var entry : registry.tools().values()) {
            var tool = new LinkedHashMap<String, Object>();
            tool.put("name", entry.name());
            registry.toolMetadata(entry.name()).ifPresent(meta -> {
                if (!meta.title().isEmpty()) {
                    tool.put("title", meta.title());
                }
                if (!meta.iconUrl().isEmpty()) {
                    // MCP 2025-11-25: icons is an array of objects with src/sizes/type.
                    // We expose a single-icon shorthand here; consumers needing
                    // multiple sizes/srcsets can register the metadata directly.
                    tool.put("icons", List.of(Map.of("src", meta.iconUrl())));
                }
                if (!meta.meta().isEmpty()) {
                    tool.put("_meta", meta.meta());
                }
            });
            if (!entry.description().isEmpty()) {
                tool.put("description", entry.description());
            }
            tool.put("inputSchema", McpRegistry.inputSchema(entry));
            toolList.add(tool);
        }
        return JsonRpc.Response.success(id, Map.of("tools", toolList));
    }

    private JsonRpc.Response handleToolsCall(AtmosphereResource resource, Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing tool name");
        }
        var toolName = params.get("name").stringValue();
        var toolOpt = registry.tool(toolName);

        // Task-augmented call (MCP 2025-11-25 experimental). When the
        // requestor includes params.task, we accept the task, return a
        // CreateTaskResult immediately, and run the underlying tool
        // off-thread so subsequent tasks/get / tasks/result poll it.
        if (toolOpt.isPresent() && params.has("task")) {
            return acceptToolCallAsTask(resource, id, toolOpt.get(), params);
        }
        if (toolOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown tool: " + toolName);
        }

        var tool = toolOpt.get();
        var arguments = params.has("arguments") ? params.get("arguments") : null;
        int argCount = arguments != null ? arguments.size() : 0;
        var principalName = resolvePrincipal(resource);

        // MCP security gateway. Consult the governance plane (if
        // atmosphere-ai is on the classpath) before dispatching the tool
        // handler. MS-schema YAML rules over `tool_name` fire for MCP
        // invocations the same way they do for first-party @AiTool
        // dispatches. Partial coverage of OWASP Agentic Top-10 A08.
        var framework = config != null ? config.framework() : null;
        var previewArgs = jsonNodeToPreview(arguments);
        var gateOutcome = McpPolicyGateway.admit(framework, toolName, previewArgs);
        if (gateOutcome instanceof McpPolicyGateway.Outcome.Denied denied) {
            logger.warn("MCP tool '{}' denied by policy '{}': {}",
                    toolName, denied.policyName(), denied.reason());
            return JsonRpc.Response.error(id, JsonRpc.INVALID_REQUEST,
                    "Tool '" + toolName + "' denied by policy '" + denied.policyName()
                            + "': " + denied.reason());
        }

        try {
            if (tracing != null) {
                return tracing.traced("tool", toolName, argCount,
                        () -> executeToolCall(id, tool, arguments, principalName));
            }
            return executeToolCall(id, tool, arguments, principalName);
        } catch (IllegalArgumentException e) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (InvocationTargetException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Tool {} invocation failed", toolName, cause);
            return JsonRpc.Response.success(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", cause.getMessage())),
                    "isError", true
            ));
        } catch (Exception e) {
            logger.warn("Tool {} invocation failed", toolName, e);
            return JsonRpc.Response.success(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text",
                            "Tool invocation failed: " + e.getMessage())),
                    "isError", true
            ));
        }
    }

    private JsonRpc.Response executeToolCall(Object id,
                                              McpRegistry.ToolEntry tool,
                                              JsonNode arguments,
                                              String principalName) throws Exception {
        try {
            Object result;
            if (tool.isDynamic()) {
                var argMap = bindArgumentsAsMap(tool.params(), arguments);
                var handler = tool.handler();
                // Handlers that implement IdentityAwareToolHandler receive the
                // authenticated caller's principal; plain ToolHandler lambdas
                // keep the single-arg shape. AdminMcpBridge uses the
                // identity-aware form to gate writes through
                // ControlAuthorizer.authorize with a real principal instead
                // of null.
                if (handler instanceof McpRegistry.IdentityAwareToolHandler aware) {
                    result = aware.execute(argMap, principalName);
                } else {
                    result = handler.execute(argMap);
                }
            } else {
                var args = bindArguments(tool.method(), tool.params(), arguments);
                result = tool.method().invoke(tool.instance(), args);
            }

            // MCP 2025-06-18 added `structuredContent`: tools that return a
            // typed object (not a plain String) emit both the legacy text
            // content AND the structured form. Older clients ignore the
            // unknown field; newer ones consume the typed payload directly.
            var resultMap = new LinkedHashMap<String, Object>();
            String text;
            if (result instanceof String s) {
                text = s;
            } else {
                text = mapper.writeValueAsString(result);
                if (result != null) {
                    resultMap.put("structuredContent", result);
                }
            }
            resultMap.put("content", List.of(Map.of("type", "text", "text", text)));
            resultMap.put("isError", false);

            return JsonRpc.Response.success(id, resultMap);
        } catch (InvocationTargetException e) {
            throw e;
        }
    }

    /**
     * Resolve the authenticated caller for the current MCP tool
     * invocation. Reads the servlet {@link java.security.Principal} first
     * (Spring Security / Jakarta Security), then falls back to the
     * {@code ai.userId} request attribute that the AI pipeline sets.
     * Returns {@code null} for anonymous requests — downstream authorizer
     * decides whether to admit them.
     */
    private static String resolvePrincipal(AtmosphereResource resource) {
        if (resource == null || resource.getRequest() == null) {
            return null;
        }
        var req = resource.getRequest();
        var principal = req.getUserPrincipal();
        if (principal != null && principal.getName() != null
                && !principal.getName().isBlank()) {
            return principal.getName();
        }
        var attr = req.getAttribute("ai.userId");
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    // ── Tasks (durable requests, MCP 2025-11-25 experimental) ────────────

    /**
     * Accept a task-augmented {@code tools/call}. Immediately returns a
     * {@code CreateTaskResult} envelope and dispatches the actual tool
     * execution to a virtual thread. The eventual outcome is stored on the
     * task and surfaced via {@code tasks/result}.
     */
    private JsonRpc.Response acceptToolCallAsTask(AtmosphereResource resource, Object id,
                                                   McpRegistry.ToolEntry tool, JsonNode params) {
        var taskParams = params.get("task");
        long requestedTtl = taskParams != null && taskParams.has("ttl")
                ? taskParams.get("ttl").asLong(McpTaskManager.DEFAULT_TTL_MS)
                : McpTaskManager.DEFAULT_TTL_MS;
        var task = taskManager.create(requestedTtl);
        var arguments = params.has("arguments") ? params.get("arguments") : null;
        var principal = resolvePrincipal(resource);

        Thread.startVirtualThread(() -> {
            try {
                var underlying = executeToolCall(id, tool, arguments, principal);
                if (underlying.error() == null && underlying.result() instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    var resMap = (Map<String, Object>) rawMap;
                    var isError = resMap.get("isError") instanceof Boolean b && b;
                    if (isError) {
                        task.fail(resMap, "Tool reported isError=true");
                    } else {
                        task.complete(resMap, "Tool completed");
                    }
                } else {
                    var msg = underlying.error() != null ? underlying.error().message() : "Unknown failure";
                    task.fail(Map.of(
                            "content", List.of(Map.of("type", "text", "text",
                                    "Tool execution failed: " + msg)),
                            "isError", true
                    ), msg);
                }
            } catch (Exception e) {
                logger.warn("Async tool execution threw", e);
                task.fail(Map.of(
                        "content", List.of(Map.of("type", "text", "text",
                                "Tool threw: " + e.getMessage())),
                        "isError", true
                ), e.getMessage());
            }
        });

        return JsonRpc.Response.success(id, Map.of("task", task.toWire()));
    }

    private JsonRpc.Response handleTasksGet(Object id, JsonNode params) {
        if (params == null || !params.has("taskId")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing taskId");
        }
        var taskId = params.get("taskId").stringValue();
        var task = taskManager.get(taskId);
        if (task.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS,
                    "Failed to retrieve task: Task not found");
        }
        return JsonRpc.Response.success(id, task.get().toWire());
    }

    private JsonRpc.Response handleTasksResult(Object id, JsonNode params) {
        if (params == null || !params.has("taskId")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing taskId");
        }
        var taskId = params.get("taskId").stringValue();
        var taskOpt = taskManager.get(taskId);
        if (taskOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS,
                    "Failed to retrieve task: Task not found");
        }
        var task = taskOpt.get();
        try {
            // Spec: tasks/result blocks until the task reaches a terminal
            // status. With our in-process executor this is bounded by tool
            // runtime; production deployments may want a wall-clock cap.
            var resultMap = task.result().get();
            var withMeta = new LinkedHashMap<String, Object>(resultMap);
            withMeta.merge("_meta", Map.of(
                    "io.modelcontextprotocol/related-task",
                    Map.of("taskId", task.taskId())), (a, b) -> a);
            return JsonRpc.Response.success(id, withMeta);
        } catch (Exception e) {
            return JsonRpc.Response.error(id, JsonRpc.INTERNAL_ERROR,
                    "tasks/result failed: " + e.getMessage());
        }
    }

    private JsonRpc.Response handleTasksList(Object id, JsonNode params) {
        String cursor = params != null && params.has("cursor") && params.get("cursor").isString()
                ? params.get("cursor").stringValue() : null;
        var page = taskManager.list(cursor, 100);
        return JsonRpc.Response.success(id, page.toWire());
    }

    private JsonRpc.Response handleTasksCancel(Object id, JsonNode params) {
        if (params == null || !params.has("taskId")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing taskId");
        }
        var taskId = params.get("taskId").stringValue();
        var existing = taskManager.get(taskId);
        if (existing.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS,
                    "Failed to retrieve task: Task not found");
        }
        if (existing.get().status().isTerminal()) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS,
                    "Cannot cancel task: already in terminal status '"
                            + existing.get().status().wireValue() + "'");
        }
        var cancelled = taskManager.cancel(taskId, "Cancelled by request");
        return JsonRpc.Response.success(id, cancelled.get().toWire());
    }

    /** Visible-for-testing: lets tests peek at task state. */
    public McpTaskManager taskManager() {
        return taskManager;
    }

    // ── Elicitation (server → client request, MCP 2025-06-18) ────────────

    /**
     * Issue an {@code elicitation/create} request to the client behind
     * {@code session} and return a future that completes with the client's
     * response envelope. The response payload follows MCP {@code ElicitResult}
     * shape: {@code {action: "accept|decline|cancel", content: {...}}}.
     *
     * <p>Failure modes:</p>
     * <ul>
     *   <li>Client did not advertise the {@code elicitation} capability at
     *       initialize time → the future fails with
     *       {@link IllegalStateException} immediately.</li>
     *   <li>Client never replies → caller's responsibility to apply a
     *       timeout (e.g. {@link CompletableFuture#orTimeout}) and call
     *       {@link McpSession#cancelServerRequest} on expiry.</li>
     * </ul>
     *
     * @param session       the MCP session to elicit through (typically obtained
     *                      from a {@code @McpTool} handler's resource attribute)
     * @param message       human-readable prompt shown to the user
     * @param requestedSchema JSON Schema describing the expected response shape
     * @return future resolving to the {@code result}/{@code error} JSON envelope
     */
    public CompletableFuture<JsonNode> elicit(McpSession session, String message,
                                              Map<String, Object> requestedSchema) {
        if (session == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("elicit requires an active McpSession"));
        }
        var caps = session.clientCapabilities();
        if (caps == null || !caps.containsKey("elicitation")) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Client did not advertise the 'elicitation' capability at initialize. "
                            + "Cannot issue elicitation/create."));
        }

        var requestId = UUID.randomUUID().toString();
        var request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", McpMethod.ELICITATION_CREATE);
        var params = new LinkedHashMap<String, Object>();
        params.put("message", message);
        params.put("requestedSchema", requestedSchema != null ? requestedSchema : Map.of());
        request.put("params", params);

        var future = new CompletableFuture<JsonNode>();
        session.registerServerRequest(requestId, future);

        try {
            var serialized = mapper.writeValueAsString(request);
            session.addPendingNotification(serialized);
        } catch (Exception e) {
            session.cancelServerRequest(requestId, e);
            return CompletableFuture.failedFuture(e);
        }
        return future;
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
            registry.resourceMetadata(entry.uri()).ifPresent(meta -> {
                if (!meta.title().isEmpty()) {
                    res.put("title", meta.title());
                }
                if (!meta.iconUrl().isEmpty()) {
                    res.put("icons", List.of(Map.of("src", meta.iconUrl())));
                }
                if (!meta.meta().isEmpty()) {
                    res.put("_meta", meta.meta());
                }
            });
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
        var uri = params.get("uri").stringValue();
        var resOpt = registry.resource(uri);
        if (resOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown resource: " + uri);
        }

        var res = resOpt.get();
        var arguments = params.has("arguments") ? params.get("arguments") : null;
        int argCount = arguments != null ? arguments.size() : 0;

        try {
            if (tracing != null) {
                return tracing.traced("resource", uri, argCount,
                        () -> executeResourceRead(id, res, uri, arguments));
            }
            return executeResourceRead(id, res, uri, arguments);
        } catch (IllegalArgumentException e) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            logger.warn("Resource {} read failed", uri, e);
            return JsonRpc.Response.error(id, JsonRpc.INTERNAL_ERROR,
                    "Resource read failed: " + e.getMessage());
        }
    }

    private JsonRpc.Response executeResourceRead(Object id, McpRegistry.ResourceEntry res,
                                                  String uri, JsonNode arguments) throws Exception {
        Object result;
        if (res.isDynamic()) {
            var argMap = bindArgumentsAsMap(res.params(), arguments);
            result = res.handler().read(argMap);
        } else {
            var args = bindArguments(res.method(), res.params(), arguments);
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
    }

    private JsonRpc.Response handleResourcesSubscribe(AtmosphereResource resource,
                                                       Object id, JsonNode params) {
        if (params == null || !params.has("uri")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing resource URI");
        }
        var uri = params.get("uri").stringValue();
        if (registry.resource(uri).isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown resource: " + uri);
        }
        var session = getOrCreateSession(resource);
        session.addSubscription(uri);
        logger.debug("Client subscribed to resource: {}", uri);
        return JsonRpc.Response.success(id, Map.of());
    }

    private JsonRpc.Response handleResourcesUnsubscribe(AtmosphereResource resource,
                                                         Object id, JsonNode params) {
        if (params == null || !params.has("uri")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing resource URI");
        }
        var uri = params.get("uri").stringValue();
        var session = getOrCreateSession(resource);
        session.removeSubscription(uri);
        logger.debug("Client unsubscribed from resource: {}", uri);
        return JsonRpc.Response.success(id, Map.of());
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    private JsonRpc.Response handlePromptsList(Object id) {
        var promptList = new ArrayList<Map<String, Object>>();
        for (var entry : registry.prompts().values()) {
            var prompt = new LinkedHashMap<String, Object>();
            prompt.put("name", entry.name());
            registry.promptMetadata(entry.name()).ifPresent(meta -> {
                if (!meta.title().isEmpty()) {
                    prompt.put("title", meta.title());
                }
                if (!meta.iconUrl().isEmpty()) {
                    prompt.put("icons", List.of(Map.of("src", meta.iconUrl())));
                }
                if (!meta.meta().isEmpty()) {
                    prompt.put("_meta", meta.meta());
                }
            });
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

    private JsonRpc.Response handlePromptsGet(Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing prompt name");
        }
        var promptName = params.get("name").stringValue();
        var promptOpt = registry.prompt(promptName);
        if (promptOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown prompt: " + promptName);
        }

        var prompt = promptOpt.get();
        var arguments = params.has("arguments") ? params.get("arguments") : null;
        int argCount = arguments != null ? arguments.size() : 0;

        try {
            if (tracing != null) {
                return tracing.traced("prompt", promptName, argCount,
                        () -> executePromptGet(id, prompt, arguments));
            }
            return executePromptGet(id, prompt, arguments);
        } catch (IllegalArgumentException e) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            logger.warn("Prompt {} get failed", promptName, e);
            return JsonRpc.Response.error(id, JsonRpc.INTERNAL_ERROR,
                    "Prompt get failed: " + e.getMessage());
        }
    }

    private JsonRpc.Response executePromptGet(Object id, McpRegistry.PromptEntry prompt,
                                               JsonNode arguments) throws Exception {
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
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Object[] bindArguments(java.lang.reflect.Method method,
                                   List<McpRegistry.ParamEntry> paramEntries,
                                   JsonNode arguments) {
        var methodParams = method.getParameters();
        var args = new Object[methodParams.length];
        int paramIdx = 0;

        // Resolve the topic from JSON arguments (used for Broadcaster/StreamingSession injection)
        String topic = null;
        if (arguments != null && arguments.has("topic")) {
            topic = arguments.get("topic").stringValue();
        }

        for (int i = 0; i < methodParams.length; i++) {
            var type = methodParams[i].getType();
            if (McpRegistry.isInjectableType(type)) {
                args[i] = resolveInjectable(type, topic);
            } else if (paramIdx < paramEntries.size()) {
                var param = paramEntries.get(paramIdx);
                if (arguments != null && arguments.has(param.name())) {
                    args[i] = convertParam(arguments.get(param.name()), param.type());
                } else if (param.required()) {
                    throw new IllegalArgumentException(
                            "Missing required parameter: " + param.name());
                } else {
                    args[i] = defaultValue(param.type());
                }
                paramIdx++;
            }
        }
        return args;
    }

    /**
     * Resolve framework-injectable types for @McpTool method parameters.
     */
    private Object resolveInjectable(Class<?> type, String topic) {
        if (type == AtmosphereConfig.class) {
            return config;
        }
        if (type == BroadcasterFactory.class) {
            return config.getBroadcasterFactory();
        }
        if (type == AtmosphereFramework.class) {
            return config.framework();
        }
        if (type == Broadcaster.class) {
            if (topic == null) {
                throw new IllegalArgumentException(
                        "Broadcaster injection requires a 'topic' @McpParam argument");
            }
            return config.getBroadcasterFactory().lookup(topic, true);
        }
        // StreamingSession — create a BroadcasterStreamingSession wrapping the topic's Broadcaster
        try {
            var streamingSessionClass = Class.forName("org.atmosphere.ai.StreamingSession");
            if (streamingSessionClass.isAssignableFrom(type)) {
                if (topic == null) {
                    throw new IllegalArgumentException(
                            "StreamingSession injection requires a 'topic' @McpParam argument");
                }
                var broadcaster = config.getBroadcasterFactory().lookup(topic, true);
                // Use StreamingSessions.start(Broadcaster) via reflection to avoid hard compile dependency
                var factoryClass = Class.forName("org.atmosphere.ai.StreamingSessions");
                var startMethod = factoryClass.getMethod("start", Broadcaster.class);
                return startMethod.invoke(null, broadcaster);
            }
        } catch (ClassNotFoundException ex) {
            logger.trace("atmosphere-ai not on classpath — fall through", ex);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create StreamingSession for topic " + topic, e);
        }
        throw new IllegalArgumentException("Unsupported injectable type: " + type.getName());
    }

    /**
     * Flatten the incoming arguments JSON to a {@code Map<String, Object>}
     * suitable for audit preview via {@link McpPolicyGateway#admit}. Values
     * are coerced to primitives / strings; this is a best-effort snapshot,
     * not a full type-bound binding — it's only used by the governance
     * audit trail, not the tool executor.
     */
    private static Map<String, Object> jsonNodeToPreview(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, Object>();
        for (var field : arguments.properties()) {
            map.put(field.getKey(), mapper.convertValue(field.getValue(), Object.class));
        }
        return map;
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
            } else if (param.required()) {
                throw new IllegalArgumentException(
                        "Missing required parameter: " + param.name());
            } else {
                map.put(param.name(), defaultValue(param.type()));
            }
        }
        // Also include any extra arguments not declared in params
        if (arguments != null) {
            for (var field : arguments.properties()) {
                map.putIfAbsent(field.getKey(), mapper.convertValue(field.getValue(), Object.class));
            }
        }
        return map;
    }

    private Object convertParam(JsonNode node, Class<?> type) {
        if (node == null || node.isNull()) return defaultValue(type);
        if (type == String.class) return node.isString() ? node.stringValue() : node.toString();
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
        if (id.isString()) return id.stringValue();
        return id.toString();
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
        } catch (JacksonException e) {
            logger.error("Failed to serialize JSON-RPC response", e);
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Serialization failed\"}}";
        }
    }
}
