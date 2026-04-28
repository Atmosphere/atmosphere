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
package org.atmosphere.a2a.runtime;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.protocol.A2aMethod;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.a2a.types.ListTasksResponse;
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.Part;
import org.atmosphere.a2a.types.Role;
import org.atmosphere.a2a.types.SendMessageResponse;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.protocol.JsonRpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Routes A2A v1.0.0 JSON-RPC requests to the registered task and skill
 * machinery. Method names use the PascalCase form defined in the v1.0.0
 * specification ({@code SendMessage}, {@code GetTask}, etc.); pre-1.0
 * slash-style names are normalized via {@link A2aMethod#canonicalize(String)}
 * with a one-time deprecation warning per alias.
 *
 * <p>A2A-specific JSON-RPC error codes follow {@code docs/specification.md}
 * §5.4: {@code -32001} task not found, {@code -32002} task not cancelable,
 * {@code -32003} push-notification not supported, {@code -32004} unsupported
 * operation, {@code -32007} extended-card not configured.</p>
 */
public final class A2aProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(A2aProtocolHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** -32001 — TaskNotFoundError per A2A v1.0.0 §5.4. */
    public static final int ERROR_TASK_NOT_FOUND = -32001;
    /** -32002 — TaskNotCancelableError. */
    public static final int ERROR_TASK_NOT_CANCELABLE = -32002;
    /** -32003 — PushNotificationNotSupportedError. */
    public static final int ERROR_PUSH_NOT_SUPPORTED = -32003;
    /** -32004 — UnsupportedOperationError. */
    public static final int ERROR_UNSUPPORTED_OPERATION = -32004;
    /** -32007 — ExtendedAgentCardNotConfiguredError. */
    public static final int ERROR_EXTENDED_CARD_NOT_CONFIGURED = -32007;

    private final A2aRegistry registry;
    private final TaskManager taskManager;
    private final AgentCard agentCard;
    private final AgentCard extendedAgentCard;

    private final Set<String> warnedLegacyAliases = ConcurrentHashMap.newKeySet();

    public A2aProtocolHandler(A2aRegistry registry, TaskManager taskManager, AgentCard agentCard) {
        this(registry, taskManager, agentCard, null);
    }

    public A2aProtocolHandler(A2aRegistry registry, TaskManager taskManager,
                              AgentCard agentCard, AgentCard extendedAgentCard) {
        this.registry = registry;
        this.taskManager = taskManager;
        this.agentCard = agentCard;
        this.extendedAgentCard = extendedAgentCard;
    }

    public AgentCard agentCard() {
        return agentCard;
    }

    public AgentCard extendedAgentCard() {
        return extendedAgentCard;
    }

    /** Returns the agent card serialized as JSON. Used by the well-known filter. */
    public String agentCardJson() {
        try {
            return mapper.writeValueAsString(agentCard);
        } catch (Exception e) {
            return null;
        }
    }

    public String handleMessage(String message) {
        try {
            var node = mapper.readTree(message);
            var rawMethod = node.has("method") ? node.get("method").stringValue() : null;
            var id = node.has("id") ? node.get("id") : null;

            if (rawMethod == null) {
                return serialize(JsonRpc.Response.error(idValue(id),
                        JsonRpc.INVALID_REQUEST, "Missing method"));
            }

            var method = A2aMethod.canonicalize(rawMethod);
            if (!method.equals(rawMethod) && warnedLegacyAliases.add(rawMethod)) {
                logger.warn("Received pre-1.0 A2A method '{}'; aliasing to '{}'. "
                        + "Update clients to the v1.0.0 method name.", rawMethod, method);
            }

            var isNotification = id == null || id.isNull();
            var idVal = isNotification ? null : idValue(id);
            var params = node.get("params");

            var response = switch (method) {
                case A2aMethod.SEND_MESSAGE, A2aMethod.SEND_STREAMING_MESSAGE ->
                        handleSendMessage(idVal, params);
                case A2aMethod.GET_TASK -> handleGetTask(idVal, params);
                case A2aMethod.LIST_TASKS -> handleListTasks(idVal, params);
                case A2aMethod.CANCEL_TASK -> handleCancelTask(idVal, params);
                case A2aMethod.SUBSCRIBE_TO_TASK -> handleSubscribeToTask(idVal, params);
                case A2aMethod.CREATE_TASK_PUSH_NOTIFICATION_CONFIG,
                     A2aMethod.GET_TASK_PUSH_NOTIFICATION_CONFIG,
                     A2aMethod.LIST_TASK_PUSH_NOTIFICATION_CONFIGS,
                     A2aMethod.DELETE_TASK_PUSH_NOTIFICATION_CONFIG ->
                        JsonRpc.Response.error(idVal, ERROR_PUSH_NOT_SUPPORTED,
                                "Push notifications are not supported by this agent");
                case A2aMethod.GET_EXTENDED_AGENT_CARD -> handleGetExtendedAgentCard(idVal);
                default -> JsonRpc.Response.error(idVal, JsonRpc.METHOD_NOT_FOUND,
                        "Unknown method: " + rawMethod);
            };

            if (isNotification) {
                return null;
            }
            return serialize(response);
        } catch (JacksonException e) {
            logger.warn("Failed to parse A2A message", e);
            return serialize(JsonRpc.Response.error(null, JsonRpc.PARSE_ERROR,
                    "Invalid JSON: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Error handling A2A message", e);
            return serialize(JsonRpc.Response.error(null, JsonRpc.INTERNAL_ERROR,
                    e.getMessage()));
        }
    }

    /**
     * Handle a streaming message from a local transport. Executes the skill
     * and bridges each artifact text part to the token consumer.
     */
    public void handleStreamingMessage(String message, Consumer<String> onToken,
                                        Runnable onComplete) {
        try {
            var node = mapper.readTree(message);
            var params = node.get("params");

            if (params == null) {
                return;
            }

            var msg = extractMessage(params);
            var contextId = params.has("contextId")
                    ? params.get("contextId").stringValue() : UUID.randomUUID().toString();
            var skillId = resolveSkillId(msg);

            var taskCtx = taskManager.createTask(contextId);
            taskCtx.addMessage(msg);

            A2aRegistry.SkillEntry skill = null;
            if (skillId != null) {
                skill = registry.skill(skillId).orElse(null);
            }
            if (skill == null && !registry.skills().isEmpty()) {
                skill = registry.skills().values().iterator().next();
            }

            if (skill != null) {
                executeSkill(skill, taskCtx, params);
            } else {
                logger.warn("No skill found for streaming request (skillId: {})", skillId);
            }

            boolean hasTokens = false;
            for (var artifact : taskCtx.artifacts()) {
                if (artifact.parts() != null) {
                    for (var part : artifact.parts()) {
                        if (part.text() != null && !part.text().isEmpty()) {
                            hasTokens = true;
                            onToken.accept(part.text());
                        }
                    }
                }
            }
            if (!hasTokens) {
                logger.debug("Streaming execution produced no output for skillId '{}'",
                        skillId);
            }
        } catch (Exception e) {
            logger.warn("Streaming message handling failed", e);
        } finally {
            onComplete.run();
        }
    }

    private JsonRpc.Response handleSendMessage(Object id, JsonNode params) {
        if (params == null) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing params");
        }

        var message = extractMessage(params);
        var contextId = resolveContextId(params, message);
        var skillId = resolveSkillId(message);

        var taskCtx = taskManager.createTask(contextId);
        taskCtx.addMessage(message);

        if (skillId != null) {
            var skillOpt = registry.skill(skillId);
            if (skillOpt.isPresent()) {
                executeSkill(skillOpt.get(), taskCtx, params);
            } else {
                taskCtx.fail("Unknown skill: " + skillId);
            }
        } else if (!registry.skills().isEmpty()) {
            executeSkill(registry.skills().values().iterator().next(), taskCtx, params);
        } else {
            taskCtx.fail("No skills registered");
        }

        return JsonRpc.Response.success(id, SendMessageResponse.of(
                taskCtx.toTask(historyLength(params))));
    }

    private JsonRpc.Response handleGetTask(Object id, JsonNode params) {
        if (params == null || !params.has("id")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing task id");
        }
        var taskId = params.get("id").stringValue();
        var taskOpt = taskManager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return JsonRpc.Response.error(id, ERROR_TASK_NOT_FOUND,
                    "Unknown task: " + taskId);
        }
        return JsonRpc.Response.success(id, taskOpt.get().toTask(historyLength(params)));
    }

    private JsonRpc.Response handleListTasks(Object id, JsonNode params) {
        var contextId = textParam(params, "contextId");
        TaskState statusFilter = null;
        if (params != null && params.has("status") && !params.get("status").isNull()) {
            try {
                statusFilter = TaskState.fromWire(params.get("status").asString());
            } catch (IllegalArgumentException e) {
                return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS,
                        "Invalid status filter: " + params.get("status").asString());
            }
        }
        var pageSize = intParam(params, "pageSize", 50);
        if (pageSize < 1) {
            pageSize = 50;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
        var pageToken = textParam(params, "pageToken");
        var historyLength = params != null && params.has("historyLength")
                ? Integer.valueOf(params.get("historyLength").asInt()) : null;

        // Stream#toList() is unmodifiable; sort the snapshot in a mutable copy.
        var all = new java.util.ArrayList<>(taskManager.listTasks(contextId, statusFilter));
        all.sort((a, b) -> Long.compare(b.createdAtMillis(), a.createdAtMillis()));
        var startIdx = decodePageToken(pageToken);
        var endIdx = Math.min(all.size(), startIdx + pageSize);
        var page = all.subList(Math.min(startIdx, all.size()), endIdx);
        var nextToken = endIdx < all.size() ? encodePageToken(endIdx) : "";
        final var clampHistory = historyLength;
        var tasks = page.stream().map(t -> t.toTask(clampHistory)).toList();
        return JsonRpc.Response.success(id, new ListTasksResponse(
                tasks, nextToken, pageSize, all.size()));
    }

    private JsonRpc.Response handleCancelTask(Object id, JsonNode params) {
        if (params == null || !params.has("id")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing task id");
        }
        var taskId = params.get("id").stringValue();
        var existing = taskManager.getTask(taskId);
        if (existing.isEmpty()) {
            return JsonRpc.Response.error(id, ERROR_TASK_NOT_FOUND,
                    "Unknown task: " + taskId);
        }
        var canceled = taskManager.cancelTask(taskId);
        if (!canceled) {
            return JsonRpc.Response.error(id, ERROR_TASK_NOT_CANCELABLE,
                    "Task not cancelable: " + taskId);
        }
        return JsonRpc.Response.success(id, existing.get().toTask());
    }

    private JsonRpc.Response handleSubscribeToTask(Object id, JsonNode params) {
        if (params == null || !params.has("id")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing task id");
        }
        var taskId = params.get("id").stringValue();
        var taskOpt = taskManager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return JsonRpc.Response.error(id, ERROR_TASK_NOT_FOUND,
                    "Unknown task: " + taskId);
        }
        var ctx = taskOpt.get();
        if (ctx.state().isTerminal()) {
            return JsonRpc.Response.error(id, ERROR_UNSUPPORTED_OPERATION,
                    "Task is in a terminal state: " + ctx.state());
        }
        return JsonRpc.Response.success(id, ctx.toTask());
    }

    private JsonRpc.Response handleGetExtendedAgentCard(Object id) {
        // Per A2A v1.0.0 §6.4: if no separate extended card is configured, fall
        // back to the public agent card. The capability flag advertises that
        // the method is callable, not that a distinct card exists.
        return JsonRpc.Response.success(id,
                extendedAgentCard != null ? extendedAgentCard : agentCard);
    }

    private void executeSkill(A2aRegistry.SkillEntry skill, TaskContext taskCtx, JsonNode params) {
        try {
            var method = skill.method();
            method.setAccessible(true);
            var methodParams = method.getParameters();
            var args = new Object[methodParams.length];

            var arguments = params.has("arguments") ? params.get("arguments") : null;
            int paramIdx = 0;

            for (int i = 0; i < methodParams.length; i++) {
                if (methodParams[i].getType() == TaskContext.class) {
                    args[i] = taskCtx;
                } else {
                    var a2aParam = methodParams[i].getAnnotation(AgentSkillParam.class);
                    if (a2aParam != null && arguments != null && arguments.has(a2aParam.name())) {
                        args[i] = coerceArgument(arguments.get(a2aParam.name()), methodParams[i].getType());
                    } else if (paramIdx < skill.params().size()) {
                        var message = extractMessage(params);
                        if (!message.parts().isEmpty()
                                && message.parts().getFirst().text() != null) {
                            args[i] = message.parts().getFirst().text();
                        }
                        paramIdx++;
                    }
                }
            }

            method.invoke(skill.instance(), args);
        } catch (InvocationTargetException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Skill {} execution failed", skill.id(), cause);
            taskCtx.fail(cause.getMessage());
        } catch (Exception e) {
            logger.warn("Skill {} execution failed", skill.id(), e);
            taskCtx.fail(e.getMessage());
        }
    }

    private Object coerceArgument(JsonNode node, Class<?> targetType) {
        if (targetType == String.class) {
            return node.isString() ? node.stringValue() : node.toString();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return node.asInt();
        }
        if (targetType == long.class || targetType == Long.class) {
            return node.asLong();
        }
        if (targetType == double.class || targetType == Double.class) {
            return node.asDouble();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return node.asBoolean();
        }
        if (targetType == JsonNode.class) {
            return node;
        }
        try {
            return mapper.treeToValue(node, targetType);
        } catch (Exception e) {
            return node.isString() ? node.stringValue() : node.toString();
        }
    }

    private Message extractMessage(JsonNode params) {
        if (params != null && params.has("message")) {
            try {
                return mapper.treeToValue(params.get("message"), Message.class);
            } catch (JacksonException e) {
                logger.warn("Failed to parse message from params", e);
            }
        }
        return new Message(UUID.randomUUID().toString(), null, null,
                Role.USER, List.of(Part.text("")), null, null, null);
    }

    private String resolveContextId(JsonNode params, Message message) {
        if (message.contextId() != null) {
            return message.contextId();
        }
        if (params != null && params.has("contextId")) {
            return params.get("contextId").stringValue();
        }
        return UUID.randomUUID().toString();
    }

    private String resolveSkillId(Message message) {
        if (message.metadata() != null && message.metadata().containsKey("skillId")) {
            return message.metadata().get("skillId").toString();
        }
        return null;
    }

    private Integer historyLength(JsonNode params) {
        if (params == null) {
            return null;
        }
        if (params.has("historyLength")) {
            return params.get("historyLength").asInt();
        }
        if (params.has("configuration") && params.get("configuration").has("historyLength")) {
            return params.get("configuration").get("historyLength").asInt();
        }
        return null;
    }

    private static String textParam(JsonNode params, String name) {
        if (params == null || !params.has(name) || params.get(name).isNull()) {
            return null;
        }
        return params.get(name).asString();
    }

    private static int intParam(JsonNode params, String name, int defaultValue) {
        if (params == null || !params.has(name) || params.get(name).isNull()) {
            return defaultValue;
        }
        return params.get(name).asInt(defaultValue);
    }

    private static int decodePageToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(token));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encodePageToken(int offset) {
        return Integer.toString(offset);
    }

    private Object idValue(JsonNode id) {
        if (id == null || id.isNull()) {
            return null;
        }
        if (id.isNumber()) {
            return id.numberValue();
        }
        if (id.isString()) {
            return id.stringValue();
        }
        return id.toString();
    }

    private String serialize(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            logger.error("Failed to serialize A2A response", e);
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Serialization failed\"}}";
        }
    }
}
