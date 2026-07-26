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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Phase 10 of the unified {@code @Agent} API: validate tool arguments
 * supplied by the model against the {@link ToolDefinition} parameter list
 * before dispatch. Closes Correctness Invariant #4 (Boundary Safety) by
 * making argument validation a single uniform step instead of relying on
 * each runtime's framework-native validator (which silently varies in
 * strictness).
 *
 * <p>Returns a list of human-readable error messages — empty list means the
 * arguments are valid. Callers serialize the errors back to the LLM as a
 * structured tool error so the model can retry rather than blowing up the
 * runtime.</p>
 */
public final class ToolArgumentValidator {

    private ToolArgumentValidator() {
    }

    /**
     * Validate {@code arguments} against {@code tool}'s declared parameter
     * list. Checks: required parameters present, basic JSON-schema type
     * alignment for {@code string}, {@code integer}, {@code number},
     * {@code boolean}, plus the structural facets a parameter may carry —
     * {@code enum} membership, array {@code items.type}, and nested object
     * {@code properties} (recursively, including their own required list).
     * Object/array parameters with no declared structure are accepted as-is —
     * Jackson deserialization at the executor boundary handles the rest.
     *
     * @param tool      the tool definition (schema source)
     * @param arguments the LLM-supplied argument map (may be null)
     * @return list of validation errors, empty when arguments are valid
     */
    public static List<String> validate(ToolDefinition tool, Map<String, Object> arguments) {
        var errors = new ArrayList<String>();
        var args = arguments != null ? arguments : Map.<String, Object>of();
        for (var param : tool.parameters()) {
            validateParameter(param, args.get(param.name()), param.name(), errors);
        }
        return errors;
    }

    /**
     * Validate one parameter value. {@code path} is the dotted location used in
     * error messages so a nested violation names the exact field
     * ({@code 'filter.status'}) rather than just the top-level parameter.
     */
    private static void validateParameter(ToolParameter param, Object value,
                                          String path, List<String> errors) {
        if (value == null) {
            if (param.required()) {
                errors.add("missing required parameter '" + path + "'");
            }
            return;
        }
        if (!matchesJsonType(value, param.type())) {
            errors.add("parameter '" + path + "' must be a " + param.type()
                    + " but got " + value.getClass().getSimpleName());
            return;
        }
        if (!param.enumValues().isEmpty() && !param.enumValues().contains(value.toString())) {
            errors.add("parameter '" + path + "' must be one of " + param.enumValues()
                    + " but got '" + value + "'");
            return;
        }
        if (param.items() != null && value instanceof Collection<?> items) {
            // Recurse into the element schema so an array of objects has its
            // element properties validated too, not just the element's type.
            int index = 0;
            for (var item : items) {
                if (item != null) {
                    validateParameter(param.items(), item, path + "[" + index + "]", errors);
                }
                index++;
            }
            return;
        }
        if (!param.properties().isEmpty() && value instanceof Map<?, ?> nested) {
            for (var property : param.properties()) {
                validateParameter(property, nested.get(property.name()),
                        path + "." + property.name(), errors);
            }
        }
    }

    private static boolean matchesJsonType(Object value, String jsonSchemaType) {
        return switch (jsonSchemaType) {
            case "string" -> value instanceof String || value instanceof Character;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof Collection<?> || value.getClass().isArray()
                    // A raw JSON span survives the malformed-input fallback path;
                    // the executor's Jackson binding still handles it.
                    || value instanceof String;
            case "object" -> value instanceof Map<?, ?> || value instanceof String;
            // null/unknown types pass through to the executor.
            default -> true;
        };
    }
}
