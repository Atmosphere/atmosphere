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
package org.atmosphere.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.RecordComponent;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Built-in {@link StructuredOutputParser} using Jackson for JSON parsing and
 * reflection-based JSON Schema generation. Works with any instruction-following
 * LLM without requiring additional Jackson modules.
 *
 * <p>Generates a minimal JSON Schema from the target class (records and POJOs)
 * and instructs the LLM to respond with valid JSON. Parses the response using
 * Jackson's {@link ObjectMapper}.</p>
 *
 * <p>Streaming field parsing extracts individual JSON fields from partial
 * output, enabling progressive UI rendering via
 * {@link AiEvent.StructuredField} events.</p>
 */
public class JacksonStructuredOutputParser implements StructuredOutputParser {

    private static final Logger logger = LoggerFactory.getLogger(JacksonStructuredOutputParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private static final Map<Class<?>, String> JSON_TYPE_MAP = Map.ofEntries(
            Map.entry(String.class, "string"),
            Map.entry(int.class, "integer"), Map.entry(Integer.class, "integer"),
            Map.entry(long.class, "integer"), Map.entry(Long.class, "integer"),
            Map.entry(double.class, "number"), Map.entry(Double.class, "number"),
            Map.entry(float.class, "number"), Map.entry(Float.class, "number"),
            Map.entry(boolean.class, "boolean"), Map.entry(Boolean.class, "boolean")
    );

    @Override
    public String schemaInstructions(Class<?> targetType) {
        try {
            var schema = generateSchema(targetType);
            var schemaJson = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(schema);
            return "Respond with ONLY valid JSON (no markdown fences, no explanation) "
                    + "matching this JSON Schema:\n" + schemaJson;
        } catch (Exception e) {
            logger.warn("Failed to generate JSON Schema for {}, using field list fallback",
                    targetType.getSimpleName(), e);
            return buildFieldListFallback(targetType);
        }
    }

    @Override
    public <T> T parse(String llmOutput, Class<T> targetType) {
        var json = extractJson(llmOutput);
        try {
            return MAPPER.readValue(json, targetType);
        } catch (Exception e) {
            throw new StructuredOutputException(
                    "Failed to parse LLM output as " + targetType.getSimpleName()
                            + ": " + e.getMessage(), e);
        }
    }

    @Override
    public <T> Optional<Map.Entry<String, Object>> parseField(String chunk, Class<T> targetType) {
        try {
            var trimmed = chunk.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            var node = MAPPER.readTree("{" + trimmed + "}");
            var fields = node.fields();
            if (fields.hasNext()) {
                var field = fields.next();
                var value = nodeToValue(field.getValue());
                return Optional.of(new AbstractMap.SimpleImmutableEntry<>(
                        field.getKey(), value));
            }
        } catch (Exception ex) {
            logger.trace("Not a parseable field yet — expected during streaming", ex);
        }
        return Optional.empty();
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Generate a minimal JSON Schema from a Java class using reflection.
     * Supports records and POJOs.
     */
    private static Map<String, Object> generateSchema(Class<?> type) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");

        var properties = new LinkedHashMap<String, Object>();
        var required = new java.util.ArrayList<String>();

        if (type.isRecord()) {
            for (RecordComponent comp : type.getRecordComponents()) {
                properties.put(comp.getName(), typeSchema(comp.getType()));
                required.add(comp.getName());
            }
        } else {
            for (var field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    properties.put(field.getName(), typeSchema(field.getType()));
                }
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> typeSchema(Class<?> type) {
        var jsonType = JSON_TYPE_MAP.get(type);
        if (jsonType != null) {
            return Map.of("type", jsonType);
        }
        if (List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type)) {
            return Map.of("type", "array");
        }
        if (Map.class.isAssignableFrom(type)) {
            return Map.of("type", "object");
        }
        if (type.isEnum()) {
            var values = new java.util.ArrayList<String>();
            for (var constant : type.getEnumConstants()) {
                values.add(constant.toString());
            }
            return Map.of("type", "string", "enum", values);
        }
        return Map.of("type", "object");
    }

    /**
     * Extract JSON from LLM output that may include markdown fences or
     * surrounding text.
     */
    static String extractJson(String output) {
        var trimmed = output.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        var start = trimmed.indexOf('{');
        var end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private static Object nodeToValue(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private static String buildFieldListFallback(Class<?> type) {
        var sb = new StringBuilder();
        sb.append("Respond with ONLY valid JSON with these fields:\n");
        if (type.isRecord()) {
            for (RecordComponent comp : type.getRecordComponents()) {
                sb.append("- \"").append(comp.getName()).append("\": ")
                        .append(comp.getType().getSimpleName()).append("\n");
            }
        } else {
            for (var field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    sb.append("- \"").append(field.getName()).append("\": ")
                            .append(field.getType().getSimpleName()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
