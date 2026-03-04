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
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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
        return switch (param.type()) {
            case "integer" -> JsonIntegerSchema.builder()
                    .description(param.description())
                    .build();
            case "number" -> JsonNumberSchema.builder()
                    .description(param.description())
                    .build();
            case "boolean" -> JsonBooleanSchema.builder()
                    .description(param.description())
                    .build();
            default -> JsonStringSchema.builder()
                    .description(param.description())
                    .build();
        };
    }

    /**
     * Execute tool calls requested by the model and return result messages.
     *
     * @param aiMessage the AI message containing tool execution requests
     * @param toolMap   map of tool name to Atmosphere ToolDefinition
     * @return list of tool execution result messages to feed back to the model
     */
    public static List<ToolExecutionResultMessage> executeToolCalls(
            AiMessage aiMessage, Map<String, ToolDefinition> toolMap) {

        return aiMessage.toolExecutionRequests().stream()
                .map(request -> executeToolCall(request, toolMap))
                .toList();
    }

    private static ToolExecutionResultMessage executeToolCall(
            ToolExecutionRequest request, Map<String, ToolDefinition> toolMap) {

        var tool = toolMap.get(request.name());
        if (tool == null) {
            logger.warn("Tool not found: {}", request.name());
            return ToolExecutionResultMessage.from(
                    request, "{\"error\":\"Tool not found: " + request.name() + "\"}");
        }

        try {
            Map<String, Object> args = parseJsonArgs(request.arguments());
            var result = tool.executor().execute(args);
            var resultStr = result != null ? result.toString() : "null";
            logger.debug("Tool {} executed: {}", request.name(), resultStr);
            return ToolExecutionResultMessage.from(request, resultStr);
        } catch (Exception e) {
            logger.error("Tool {} execution failed", request.name(), e);
            return ToolExecutionResultMessage.from(
                    request, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Build a tool map from a list of definitions for quick lookup.
     */
    public static Map<String, ToolDefinition> toToolMap(List<ToolDefinition> tools) {
        var map = new HashMap<String, ToolDefinition>();
        for (var tool : tools) {
            map.put(tool.name(), tool);
        }
        return map;
    }

    /**
     * Minimal JSON object parser for tool arguments.
     */
    static Map<String, Object> parseJsonArgs(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        var result = new HashMap<String, Object>();
        var trimmed = json.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return result;
        }

        int i = 0;
        while (i < trimmed.length()) {
            while (i < trimmed.length() && (trimmed.charAt(i) == ',' || trimmed.charAt(i) == ' ')) {
                i++;
            }
            if (i >= trimmed.length()) {
                break;
            }
            if (trimmed.charAt(i) != '"') {
                break;
            }
            int keyStart = i + 1;
            int keyEnd = trimmed.indexOf('"', keyStart);
            if (keyEnd < 0) {
                break;
            }
            var key = trimmed.substring(keyStart, keyEnd);
            i = keyEnd + 1;

            while (i < trimmed.length() && (trimmed.charAt(i) == ':' || trimmed.charAt(i) == ' ')) {
                i++;
            }
            if (i >= trimmed.length()) {
                break;
            }

            if (trimmed.charAt(i) == '"') {
                int valStart = i + 1;
                int valEnd = findUnescapedQuote(trimmed, valStart);
                result.put(key, trimmed.substring(valStart, valEnd));
                i = valEnd + 1;
            } else if (trimmed.charAt(i) == 't' || trimmed.charAt(i) == 'f') {
                if (trimmed.startsWith("true", i)) {
                    result.put(key, true);
                    i += 4;
                } else {
                    result.put(key, false);
                    i += 5;
                }
            } else if (trimmed.charAt(i) == 'n') {
                result.put(key, null);
                i += 4;
            } else {
                int numStart = i;
                while (i < trimmed.length() && trimmed.charAt(i) != ',' && trimmed.charAt(i) != '}') {
                    i++;
                }
                var numStr = trimmed.substring(numStart, i).trim();
                if (numStr.contains(".")) {
                    result.put(key, Double.parseDouble(numStr));
                } else {
                    result.put(key, Long.parseLong(numStr));
                }
            }
        }
        return result;
    }

    private static int findUnescapedQuote(String s, int from) {
        int i = from;
        while (i < s.length()) {
            if (s.charAt(i) == '\\') {
                i += 2;
            } else if (s.charAt(i) == '"') {
                return i;
            } else {
                i++;
            }
        }
        return s.length();
    }
}
