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
import org.atmosphere.a2a.types.Message;
import org.atmosphere.a2a.types.Part;
import org.atmosphere.protocol.JsonRpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Routes incoming A2A JSON-RPC requests to the appropriate task management and skill
 * execution logic. Supports {@code message/send}, {@code message/stream}, {@code tasks/get},
 * {@code tasks/list}, {@code tasks/cancel}, and {@code agent/card} methods.
 */
public final class A2aProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(A2aProtocolHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final A2aRegistry registry;
    private final TaskManager taskManager;
    private final AgentCard agentCard;
    private volatile A2aTracing tracing;

    public A2aProtocolHandler(A2aRegistry registry, TaskManager taskManager, AgentCard agentCard) {
        this.registry = registry;
        this.taskManager = taskManager;
        this.agentCard = agentCard;
    }

    public void setTracing(A2aTracing tracing) {
        this.tracing = tracing;
    }

    public AgentCard agentCard() {
        return agentCard;
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
            var method = node.has("method") ? node.get("method").stringValue() : null;
            var id = node.has("id") ? node.get("id") : null;

            if (method == null) {
                return serialize(JsonRpc.Response.error(idValue(id),
                        JsonRpc.INVALID_REQUEST, "Missing method"));
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
                case A2aMethod.GET_AGENT_CARD -> JsonRpc.Response.success(idVal, agentCard);
                default -> JsonRpc.Response.error(idVal, JsonRpc.METHOD_NOT_FOUND,
                        "Unknown method: " + method);
            };

            // JSON-RPC notifications (no id) are dispatched but produce no response
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
     *
     * @param message    JSON-RPC request string
     * @param onToken    callback for each text token
     * @param onComplete callback when execution finishes
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

            // Stream all artifact text parts to the consumer
            boolean hasTokens = false;
            for (var artifact : taskCtx.artifacts()) {
                if (artifact.parts() != null) {
                    for (var part : artifact.parts()) {
                        if (part instanceof Part.TextPart tp) {
                            hasTokens = true;
                            onToken.accept(tp.text());
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
        var contextId = params.has("contextId")
                ? params.get("contextId").stringValue() : UUID.randomUUID().toString();
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
            // Default to first skill if none specified
            var firstSkill = registry.skills().values().iterator().next();
            executeSkill(firstSkill, taskCtx, params);
        } else {
            taskCtx.fail("No skills registered");
        }

        return JsonRpc.Response.success(id, taskCtx.toTask());
    }

    private JsonRpc.Response handleGetTask(Object id, JsonNode params) {
        if (params == null || !params.has("id")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing task id");
        }
        var taskId = params.get("id").stringValue();
        var taskOpt = taskManager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return JsonRpc.Response.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown task: " + taskId);
        }
        return JsonRpc.Response.success(id, taskOpt.get().toTask());
    }

    private JsonRpc.Response handleListTasks(Object id, JsonNode params) {
        var contextId = params != null && params.has("contextId")
                ? params.get("contextId").stringValue() : null;
        var tasks = taskManager.listTasks(contextId).stream()
                .map(TaskContext::toTask).toList();
        return JsonRpc.Response.success(id, tasks);
    }

    private JsonRpc.Response handleCancelTask(Object id, JsonNode params) {
        if (params == null || !params.has("id")) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS, "Missing task id");
        }
        var taskId = params.get("id").stringValue();
        var canceled = taskManager.cancelTask(taskId);
        if (!canceled) {
            return JsonRpc.Response.error(id, JsonRpc.INVALID_PARAMS,
                    "Task not cancellable: " + taskId);
        }
        var task = taskManager.getTask(taskId);
        return JsonRpc.Response.success(id, task.map(TaskContext::toTask).orElse(null));
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
                        var argNode = arguments.get(a2aParam.name());
                        args[i] = coerceArgument(argNode, methodParams[i].getType());
                    } else if (paramIdx < skill.params().size()) {
                        var message = extractMessage(params);
                        if (!message.parts().isEmpty()
                                && message.parts().getFirst() instanceof Part.TextPart tp) {
                            args[i] = tp.text();
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

    /**
     * Coerces a JSON argument to the declared parameter type.
     * Supports String, primitives, boxed primitives, and Jackson-deserializable objects.
     */
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
        // For complex types, attempt Jackson deserialization
        try {
            return mapper.treeToValue(node, targetType);
        } catch (Exception e) {
            // Fall back to string representation
            return node.isString() ? node.stringValue() : node.toString();
        }
    }

    private Message extractMessage(JsonNode params) {
        if (params.has("message")) {
            try {
                return mapper.treeToValue(params.get("message"), Message.class);
            } catch (JacksonException e) {
                logger.warn("Failed to parse message from params", e);
            }
        }
        return Message.user("");
    }

    private String resolveSkillId(Message message) {
        if (message.metadata() != null && message.metadata().containsKey("skillId")) {
            return message.metadata().get("skillId").toString();
        }
        return null;
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
