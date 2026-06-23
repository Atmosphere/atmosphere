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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
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
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

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
    public String jsonSchema(Class<?> targetType) {
        if (targetType == null || targetType == Void.class) {
            return null;
        }
        try {
            // Compact (non-pretty) raw schema for the wire — this is the object a
            // runtime threads into its provider's native structured-output field,
            // not prompt prose. Strict-mode-valid (additionalProperties:false +
            // full required list + recursed nested records) so OpenAI / Anthropic
            // / Gemini strict enforcement accepts it.
            return MAPPER.writeValueAsString(generateSchema(targetType));
        } catch (Exception e) {
            // Null signals "no machine-readable schema" → the runtime stays on the
            // prompt-injection path rather than POSTing a malformed native schema.
            logger.debug("Failed to render raw JSON Schema for {}; native structured "
                    + "output will be skipped for this type", targetType.getSimpleName(), e);
            return null;
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
            var fieldSet = node.properties();
            if (!fieldSet.isEmpty()) {
                var field = fieldSet.iterator().next();
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
            Class.forName("tools.jackson.databind.ObjectMapper");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Generate a JSON Schema from a Java class using reflection. Supports records
     * and POJOs and recurses into nested record/POJO property types.
     *
     * <p>The output is valid for provider <em>strict</em> structured-output modes
     * (OpenAI {@code json_schema} {@code strict:true}, Anthropic {@code output_config},
     * Gemini {@code responseSchema}): every object closes with
     * {@code "additionalProperties": false} and lists <em>all</em> of its
     * properties in {@code "required"}, and every array carries an {@code "items"}
     * schema. A reflective cycle (a type that transitively contains itself) is cut
     * with an open object so generation always terminates — such recursive schemas
     * may not be accepted by every provider's strict mode, which is exactly what
     * the {@link NativeStructuredOutputMode#AUTO} graceful fall-back exists for.</p>
     */
    private static Map<String, Object> generateSchema(Class<?> type) {
        return objectSchema(type, new java.util.IdentityHashMap<>());
    }

    /**
     * Build the schema for an object type, guarding against reflective cycles via
     * the path-scoped {@code seen} set (put-on-descend, remove-on-return — so two
     * sibling fields of the same type are both expanded, but a type nested inside
     * itself is cut).
     */
    private static Map<String, Object> objectSchema(Class<?> type, Map<Class<?>, Boolean> seen) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        if (seen.put(type, Boolean.TRUE) != null) {
            // Cycle: this type is already being expanded higher up the path. Emit
            // an open object instead of descending forever.
            schema.put("additionalProperties", true);
            return schema;
        }

        var properties = new LinkedHashMap<String, Object>();
        var required = new java.util.ArrayList<String>();
        if (type.isRecord()) {
            for (RecordComponent comp : type.getRecordComponents()) {
                properties.put(comp.getName(), typeSchema(comp.getGenericType(), comp.getType(), seen));
                required.add(comp.getName());
            }
        } else {
            for (var field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    properties.put(field.getName(),
                            typeSchema(field.getGenericType(), field.getType(), seen));
                    required.add(field.getName());
                }
            }
        }
        seen.remove(type);

        schema.put("properties", properties);
        // Strict mode requires EVERY property to be listed in "required".
        schema.put("required", required);
        // Strict mode requires objects to be closed.
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> typeSchema(java.lang.reflect.Type generic,
                                                  Class<?> raw, Map<Class<?>, Boolean> seen) {
        var jsonType = JSON_TYPE_MAP.get(raw);
        if (jsonType != null) {
            return Map.of("type", jsonType);
        }
        if (raw.isEnum()) {
            var values = new java.util.ArrayList<String>();
            for (var constant : raw.getEnumConstants()) {
                values.add(constant.toString());
            }
            return Map.of("type", "string", "enum", values);
        }
        if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)) {
            var element = elementType(generic);
            var array = new LinkedHashMap<String, Object>();
            array.put("type", "array");
            array.put("items", element != null
                    ? typeSchema(element, element, seen)
                    : Map.of("type", "string"));
            return array;
        }
        if (raw.isArray()) {
            var component = raw.getComponentType();
            var array = new LinkedHashMap<String, Object>();
            array.put("type", "array");
            array.put("items", typeSchema(component, component, seen));
            return array;
        }
        if (Map.class.isAssignableFrom(raw)) {
            // Arbitrary string-keyed maps cannot be closed with named properties;
            // leave them open. Strict providers that reject open maps trip the
            // NativeStructuredOutputMode.AUTO fall-back rather than failing hard.
            var map = new LinkedHashMap<String, Object>();
            map.put("type", "object");
            map.put("additionalProperties", true);
            return map;
        }
        if (raw == Object.class || raw == String.class) {
            return Map.of("type", "string");
        }
        // Nested record / POJO — recurse so the native schema is complete.
        return objectSchema(raw, seen);
    }

    /**
     * Resolve the element {@link Class} of a {@code List<E>} / {@code Set<E>}
     * generic type, or {@code null} when the collection is raw or the element is
     * itself parameterized (we keep one level — deeper generic element schemas
     * fall back to {@code string}, and the AUTO graceful path covers any provider
     * rejection).
     */
    private static Class<?> elementType(java.lang.reflect.Type generic) {
        if (generic instanceof java.lang.reflect.ParameterizedType pt) {
            var args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) {
                return c;
            }
        }
        return null;
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
        if (node.isString()) {
            return node.stringValue();
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
