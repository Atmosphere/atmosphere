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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Bridges Atmosphere {@link ToolDefinition} to Spring AI {@link ToolCallback}.
 *
 * <p>Spring AI handles the tool call loop automatically — when the model
 * requests a tool call, it invokes the callback and feeds the result back.
 * This bridge simply wraps our {@link org.atmosphere.ai.tool.ToolExecutor}
 * in Spring AI's callback contract.</p>
 */
public final class SpringAiToolBridge {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiToolBridge.class);

    private SpringAiToolBridge() {
    }

    /**
     * Convert Atmosphere tool definitions to Spring AI tool callbacks.
     *
     * @param tools the framework-agnostic tool definitions
     * @return Spring AI callbacks ready for {@code promptSpec.toolCallbacks(...)}
     */
    public static List<ToolCallback> toToolCallbacks(List<ToolDefinition> tools) {
        return tools.stream()
                .map(SpringAiToolBridge::toToolCallback)
                .toList();
    }

    private static ToolCallback toToolCallback(ToolDefinition tool) {
        var springToolDef = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(buildInputSchema(tool.parameters()))
                .build();

        return new AtmosphereToolCallback(springToolDef, tool);
    }

    /**
     * Build a JSON Schema string from the parameter list.
     * Spring AI expects the inputSchema as a JSON string.
     */
    static String buildInputSchema(List<ToolParameter> parameters) {
        if (parameters.isEmpty()) {
            return """
                    {"type":"object","properties":{},"required":[]}""";
        }

        var props = new StringJoiner(",");
        var required = new StringJoiner(",");

        for (var param : parameters) {
            props.add(String.format(
                    "\"%s\":{\"type\":\"%s\",\"description\":\"%s\"}",
                    param.name(),
                    param.type(),
                    escapeJson(param.description())
            ));
            if (param.required()) {
                required.add("\"" + param.name() + "\"");
            }
        }

        return String.format(
                "{\"type\":\"object\",\"properties\":{%s},\"required\":[%s]}",
                props, required
        );
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * ToolCallback implementation that delegates to an Atmosphere ToolExecutor.
     */
    private static final class AtmosphereToolCallback implements ToolCallback {

        private final org.springframework.ai.tool.definition.ToolDefinition springToolDef;
        private final ToolDefinition atmosphereTool;

        AtmosphereToolCallback(
                org.springframework.ai.tool.definition.ToolDefinition springToolDef,
                ToolDefinition atmosphereTool) {
            this.springToolDef = springToolDef;
            this.atmosphereTool = atmosphereTool;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return springToolDef;
        }

        @Override
        public String call(String toolInput) {
            try {
                Map<String, Object> args = parseJsonArgs(toolInput);
                var result = atmosphereTool.executor().execute(args);
                logger.debug("Tool {} executed: {}", atmosphereTool.name(), result);
                return result != null ? result.toString() : "null";
            } catch (Exception e) {
                logger.error("Tool {} execution failed", atmosphereTool.name(), e);
                return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            }
        }
    }

    /**
     * Minimal JSON object parser for tool arguments.
     * Spring AI passes a JSON string like {"key":"value","num":42}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJsonArgs(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        // Use a simple approach: the arguments are flat key-value pairs
        // For production, this could use Jackson if available
        var result = new HashMap<String, Object>();
        var trimmed = json.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return result;
        }

        // Simple tokenizer for flat JSON objects
        int i = 0;
        while (i < trimmed.length()) {
            // Skip whitespace and commas
            while (i < trimmed.length() && (trimmed.charAt(i) == ',' || trimmed.charAt(i) == ' ')) {
                i++;
            }
            if (i >= trimmed.length()) {
                break;
            }

            // Parse key
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

            // Skip colon and whitespace
            while (i < trimmed.length() && (trimmed.charAt(i) == ':' || trimmed.charAt(i) == ' ')) {
                i++;
            }

            // Parse value
            if (i >= trimmed.length()) {
                break;
            }
            if (trimmed.charAt(i) == '"') {
                // String value
                int valStart = i + 1;
                int valEnd = findUnescapedQuote(trimmed, valStart);
                result.put(key, trimmed.substring(valStart, valEnd));
                i = valEnd + 1;
            } else if (trimmed.charAt(i) == 't' || trimmed.charAt(i) == 'f') {
                // Boolean
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
                // Number
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
