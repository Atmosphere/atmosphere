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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.tool.ToolBridgeUtils;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges Atmosphere {@link ToolDefinition} to LangChain4j {@link ToolSpecification}
 * and handles tool execution when the model requests tool calls.
 *
 * <p>Unlike Spring AI, LangChain4j does not automatically execute tool callbacks.
 * When the model responds with {@link ToolExecutionRequest}s, the caller must
 * execute the tools and re-submit the results. This bridge provides both the
 * specification conversion and the execution logic.</p>
 */
public final class LangChain4jToolBridge {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jToolBridge.class);

    private LangChain4jToolBridge() {
    }

    /**
     * Convert Atmosphere tool definitions to LangChain4j tool specifications.
     *
     * @param tools the framework-agnostic tool definitions
     * @return LangChain4j specifications for {@code ChatRequest.builder().toolSpecifications(...)}
     */
    public static List<ToolSpecification> toToolSpecifications(List<ToolDefinition> tools) {
        return tools.stream()
                .map(LangChain4jToolBridge::toToolSpecification)
                .toList();
    }

    private static ToolSpecification toToolSpecification(ToolDefinition tool) {
        var builder = ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description());

        if (!tool.parameters().isEmpty()) {
            builder.parameters(buildParameterSchema(tool.parameters()));
        }

        return builder.build();
    }

    private static JsonObjectSchema buildParameterSchema(List<ToolParameter> parameters) {
        var properties = new LinkedHashMap<String, JsonSchemaElement>();
        var required = new java.util.ArrayList<String>();

        for (var param : parameters) {
            properties.put(param.name(), toSchemaElement(param));
            if (param.required()) {
                required.add(param.name());
            }
        }

        return JsonObjectSchema.builder()
                .addProperties(properties)
                .required(required)
                .build();
    }

    private static JsonSchemaElement toSchemaElement(ToolParameter param) {
        // Structural facets first: an enum is a closed string set, an array
        // carries its element type, and an object carries its properties.
        // Falling straight to the scalar switch (as this did before) told the
        // model "string"/"object" with no contract at all.
        if (!param.enumValues().isEmpty()) {
            return JsonEnumSchema.builder()
                    .description(param.description())
                    .enumValues(param.enumValues())
                    .build();
        }
        if ("array".equals(param.type())) {
            return JsonArraySchema.builder()
                    .description(param.description())
                    // Recursive: an array of objects keeps its element schema
                    // instead of collapsing to a bare scalar type.
                    .items(param.items() != null
                            ? toSchemaElement(param.items())
                            : scalarSchema(null, ""))
                    .build();
        }
        if (!param.properties().isEmpty()) {
            var nested = new LinkedHashMap<String, JsonSchemaElement>();
            var nestedRequired = new java.util.ArrayList<String>();
            for (var property : param.properties()) {
                nested.put(property.name(), toSchemaElement(property));
                if (property.required()) {
                    nestedRequired.add(property.name());
                }
            }
            return JsonObjectSchema.builder()
                    .description(param.description())
                    .addProperties(nested)
                    .required(nestedRequired)
                    .build();
        }
        return scalarSchema(param.type(), param.description());
    }

    /** Scalar element for a parameter type; a null/unknown type defaults to string. */
    private static JsonSchemaElement scalarSchema(String type, String description) {
        return switch (type == null ? "string" : type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /**
     * Execute tool calls requested by the model and return result messages.
     * Routes every invocation through {@link ToolExecutionHelper#executeWithApproval}
     * so tools marked with {@code @RequiresApproval} park the virtual thread on
     * the session-scoped {@link ApprovalStrategy}. Fires
     * {@link org.atmosphere.ai.AgentLifecycleListener#onToolCall} /
     * {@link org.atmosphere.ai.AgentLifecycleListener#onToolResult} on every
     * listener in {@code listeners} around each tool invocation.
     *
     * @param aiMessage the AI message containing tool execution requests
     * @param toolMap   map of tool name to Atmosphere ToolDefinition
     * @param session   the streaming session (for emitting approval events)
     * @param strategy  session-scoped HITL gate (may be null — falls back to direct execution)
     * @param listeners lifecycle listeners that observe per-tool events (may be null or empty)
     * @return list of tool execution result messages to feed back to the model
     */
    public static List<ToolExecutionResultMessage> executeToolCalls(
            AiMessage aiMessage, Map<String, ToolDefinition> toolMap,
            StreamingSession session, ApprovalStrategy strategy,
            List<org.atmosphere.ai.AgentLifecycleListener> listeners,
            org.atmosphere.ai.approval.ToolApprovalPolicy policy) {

        return aiMessage.toolExecutionRequests().stream()
                .map(request -> executeToolCall(request, toolMap, session, strategy, listeners, policy))
                .toList();
    }

    private static ToolExecutionResultMessage executeToolCall(
            ToolExecutionRequest request, Map<String, ToolDefinition> toolMap,
            StreamingSession session, ApprovalStrategy strategy,
            List<org.atmosphere.ai.AgentLifecycleListener> listeners,
            org.atmosphere.ai.approval.ToolApprovalPolicy policy) {

        var tool = toolMap.get(request.name());
        if (tool == null) {
            logger.warn("Tool not found: {}", request.name());
            var errorResult = "{\"error\":\"Tool not found: " + request.name() + "\"}";
            org.atmosphere.ai.AgentLifecycleListener.fireToolResult(
                    listeners, request.name(), errorResult);
            return ToolExecutionResultMessage.from(request, errorResult);
        }

        Map<String, Object> args = ToolBridgeUtils.parseJsonArgs(request.arguments());
        org.atmosphere.ai.AgentLifecycleListener.fireToolCall(listeners, request.name(), args);
        var resultStr = ToolExecutionHelper.executeWithApproval(
                request.name(), tool, args, session, strategy, policy);
        org.atmosphere.ai.AgentLifecycleListener.fireToolResult(listeners, request.name(), resultStr);
        return ToolExecutionResultMessage.from(request, resultStr);
    }

    /**
     * Build a tool map from a list of definitions for quick lookup.
     *
     * <p>Delegates to {@link ToolExecutionHelper#toToolMap(List)}.</p>
     */
    public static Map<String, ToolDefinition> toToolMap(List<ToolDefinition> tools) {
        return ToolExecutionHelper.toToolMap(tools);
    }
}
