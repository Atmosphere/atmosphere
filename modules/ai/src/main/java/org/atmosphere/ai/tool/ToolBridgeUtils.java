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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utility methods for tool bridge implementations. Extracted from
 * {@code SpringAiToolBridge} and {@code LangChain4jToolBridge} to eliminate
 * code duplication across adapter modules.
 *
 * <p>Provides the shared tool-argument JSON parser and JSON Schema string
 * builders used by adapters that need a raw JSON Schema representation
 * (e.g., Spring AI's {@code inputSchema}).</p>
 */
public final class ToolBridgeUtils {

    /**
     * Tool arguments come off the model wire, so they are parsed with Jackson
     * rather than by hand (Correctness Invariant #4, Boundary Safety).
     * {@code USE_LONG_FOR_INTS} keeps the long-boxing contract callers and
     * tests rely on for integral values.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private ToolBridgeUtils() {
    }

    /**
     * Parse a model-supplied tool-argument JSON object.
     * AI frameworks pass tool arguments as a JSON string like
     * {@code {"key":"value","num":42}}.
     *
     * <p>Parsing is real JSON parsing: escape sequences (newline, quote,
     * backslash, and {@code &#92;u} unicode escapes including surrogate
     * pairs) are decoded, and nested objects / arrays become {@link Map} / {@link List}
     * values rather than raw JSON text. Before this, a hand-rolled tokenizer
     * left escapes literal and handed nested values back as un-parsed strings,
     * silently corrupting arguments on every runtime that funnels through this
     * seam.</p>
     *
     * <p><b>Never throws</b> (Correctness Invariant #4): a model can emit
     * malformed JSON, and a tool bridge must not blow up on it. Input Jackson
     * rejects falls back to a lenient best-effort tokenizer that returns
     * whatever key/value pairs it could recover — the same partial-map
     * behavior this method has always had.</p>
     *
     * @param json the JSON string to parse
     * @return a mutable map of parsed arguments, or an empty immutable map
     *         if the input is null, blank, or empty JSON
     */
    public static Map<String, Object> parseJsonArgs(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        try {
            var parsed = MAPPER.readValue(json, MAP_TYPE);
            // Copy into a HashMap: the returned map is documented mutable, and
            // Jackson's map implementation is not guaranteed to be.
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (RuntimeException e) {
            // Jackson 3 exceptions are unchecked (JacksonException extends
            // RuntimeException). Malformed model output degrades to the
            // lenient parse instead of propagating into the tool bridge.
            return lenientParse(json);
        }
    }

    /**
     * Render a parsed argument value back to JSON text — used when a
     * structured value (nested object / array) is bound to a {@code String}
     * tool parameter, which must receive JSON rather than a Java
     * {@code toString()} rendering. Falls back to {@code toString()} only if
     * serialization fails, so argument binding never throws.
     *
     * @param value the parsed value to render
     * @return the value as JSON text
     */
    public static String toJsonText(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    /**
     * Bind a parsed structured value (a {@link Map} from the model's
     * arguments) to a typed tool parameter — a record, or any POJO Jackson can
     * construct. Returns the original value when binding is not possible, so a
     * mismatch surfaces as the pre-existing argument-type error rather than a
     * new exception shape.
     *
     * @param value      the parsed value
     * @param targetType the tool parameter's declared type
     * @return the bound instance, or {@code value} when it cannot be bound
     */
    public static Object convertValue(Object value, Class<?> targetType) {
        try {
            return MAPPER.convertValue(value, targetType);
        } catch (RuntimeException e) {
            return value;
        }
    }

    /**
     * Best-effort tokenizer for input Jackson could not parse. Recovers the
     * key/value pairs it can and stops at the first construct it cannot read,
     * returning a partial map rather than throwing. Kept as the fallback so a
     * model that emits slightly-off JSON (an unquoted number token, a
     * truncated object) behaves exactly as it did before Jackson parsing was
     * introduced.
     */
    private static Map<String, Object> lenientParse(String json) {
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
                // Skip the escaped character, but never advance past the end:
                // a trailing backslash in malformed input must not overshoot
                // s.length() (boundary safety — Correctness Invariant #4).
                i += (i + 1 < s.length()) ? 2 : 1;
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
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var param : parameters) {
            properties.put(param.name(), parameterSchema(param));
            if (param.required()) {
                required.add(param.name());
            }
        }
        schema.put("properties", properties);
        schema.put("required", required);
        return toJsonText(schema);
    }

    /**
     * One parameter as a JSON Schema property map, carrying {@code enum} for a
     * constrained value, {@code items} for array elements, and nested
     * {@code properties}/{@code required} for objects — the constructs a model
     * needs to emit a valid argument. Recurses through the parameter's nesting.
     */
    private static Map<String, Object> parameterSchema(ToolParameter param) {
        var prop = new LinkedHashMap<String, Object>();
        prop.put("type", param.type());
        prop.put("description", param.description() == null ? "" : param.description());
        if (param.hasEnumValues()) {
            prop.put("enum", List.copyOf(param.enumValues()));
        }
        if (param.items() != null) {
            prop.put("items", parameterSchema(param.items()));
        }
        if (param.hasProperties()) {
            var nested = new LinkedHashMap<String, Object>();
            var nestedRequired = new ArrayList<String>();
            for (var child : param.properties()) {
                nested.put(child.name(), parameterSchema(child));
                if (child.required()) {
                    nestedRequired.add(child.name());
                }
            }
            prop.put("properties", nested);
            prop.put("required", nestedRequired);
        }
        return prop;
    }
}
