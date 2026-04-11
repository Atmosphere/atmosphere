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
package org.atmosphere.ai.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Shared utility methods for tool bridge implementations. Extracted from
 * {@code SpringAiToolBridge} and {@code LangChain4jToolBridge} to eliminate
 * code duplication across adapter modules.
 *
 * <p>Provides a minimal JSON object parser for tool arguments and JSON Schema
 * string builders used by adapters that need a raw JSON Schema representation
 * (e.g., Spring AI's {@code inputSchema}).</p>
 */
public final class ToolBridgeUtils {

    private ToolBridgeUtils() {
    }

    /**
     * Minimal JSON object parser for tool arguments.
     * AI frameworks pass tool arguments as a JSON string like
     * {@code {"key":"value","num":42}}.
     *
     * <p>Handles flat key-value pairs with string, number, boolean, and null
     * values. For nested JSON, callers should use Jackson if available.</p>
     *
     * @param json the JSON string to parse
     * @return a mutable map of parsed arguments, or an empty immutable map
     *         if the input is null, blank, or empty JSON
     */
    public static Map<String, Object> parseJsonArgs(String json) {
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
            } else if (trimmed.charAt(i) == '{' || trimmed.charAt(i) == '[') {
                // Nested object or array: capture the raw matching-bracket span
                // as a string value so downstream tool executors that need
                // structured arguments can parse it with Jackson. Previously
                // this branch fell through to numeric parsing and Long.parseLong
                // crashed with NumberFormatException on the first '[' or '{'.
                int spanEnd = findMatchingCloseBracket(trimmed, i);
                if (spanEnd < 0) {
                    // Malformed; stop parsing but keep what we already collected.
                    break;
                }
                result.put(key, trimmed.substring(i, spanEnd + 1));
                i = spanEnd + 1;
            } else {
                // Number
                int numStart = i;
                while (i < trimmed.length() && trimmed.charAt(i) != ',' && trimmed.charAt(i) != '}') {
                    i++;
                }
                var numStr = trimmed.substring(numStart, i).trim();
                if (numStr.isEmpty()) {
                    break;
                }
                try {
                    if (numStr.contains(".")) {
                        result.put(key, Double.parseDouble(numStr));
                    } else {
                        result.put(key, Long.parseLong(numStr));
                    }
                } catch (NumberFormatException nfe) {
                    // Malformed numeric literal: store the raw token rather than
                    // throwing out of the bridge. The tool executor or validator
                    // surfaces a structured error downstream.
                    result.put(key, numStr);
                }
            }
        }
        return result;
    }

    /**
     * Find the index of the matching close bracket for an open bracket at
     * {@code from}. Tracks nesting depth for both {@code {}} and {@code []} and
     * ignores brackets that appear inside quoted strings (respecting backslash
     * escapes). Returns {@code -1} if the input is malformed.
     *
     * @param s    the string to search (must have an opening bracket at {@code from})
     * @param from the index of the opening bracket
     * @return the index of the matching close bracket, or {@code -1} if not found
     */
    public static int findMatchingCloseBracket(String s, int from) {
        if (from >= s.length()) {
            return -1;
        }
        char open = s.charAt(from);
        char close;
        if (open == '{') {
            close = '}';
        } else if (open == '[') {
            close = ']';
        } else {
            return -1;
        }
        int depth = 0;
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                // Skip quoted string using the same escape rules as the value scanner
                i = findUnescapedQuote(s, i + 1) + 1;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /**
     * Find the index of the next unescaped double-quote character.
     *
     * @param s    the string to search
     * @param from the index to start searching from
     * @return the index of the unescaped quote, or {@code s.length()} if not found
     */
    public static int findUnescapedQuote(String s, int from) {
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

    /**
     * Escape a string for use inside a JSON value.
     *
     * @param s the string to escape (may be null)
     * @return the escaped string, or empty string if input is null
     */
    public static String escapeJson(String s) {
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
     * Build a JSON Schema string from the parameter list.
     * Used by adapters that need a raw JSON Schema representation
     * (e.g., Spring AI's {@code inputSchema}).
     *
     * @param parameters the tool parameter definitions
     * @return a JSON Schema string describing the parameters
     */
    public static String buildJsonSchemaString(List<ToolParameter> parameters) {
        if (parameters.isEmpty()) {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
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
}
