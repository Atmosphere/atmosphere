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

import java.util.Collection;
import java.util.List;

/**
 * Describes a single parameter of an AI tool.
 *
 * <p>Beyond the flat {@code type}, a parameter may carry the JSON-Schema
 * constructs models actually rely on to emit valid arguments: the allowed
 * values of an enum, the element schema of an array, and the properties of a
 * nested object. Without them a model has to guess — an enum parameter
 * described only as {@code "string"} invites invented values, and an array
 * described as {@code "object"} invites the wrong shape entirely.</p>
 *
 * <p>The node is recursive: {@link #items} and {@link #properties} are
 * themselves {@code ToolParameter}s, so every emitter that already walks a
 * parameter list can walk a nested schema with the same code.</p>
 *
 * @param name        parameter name as exposed to the model
 * @param description human-readable description
 * @param type        JSON Schema type (string, integer, number, boolean, object, array)
 * @param required    whether the model must provide this parameter
 * @param enumValues  the allowed values when this parameter is an enumeration;
 *                    empty when unconstrained
 * @param items       the element schema when {@code type} is {@code array};
 *                    {@code null} otherwise
 * @param properties  the nested field schemas when {@code type} is
 *                    {@code object}; empty when unspecified
 */
public record ToolParameter(
        String name,
        String description,
        String type,
        boolean required,
        List<String> enumValues,
        ToolParameter items,
        List<ToolParameter> properties
) {

    public ToolParameter {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        properties = properties == null ? List.of() : List.copyOf(properties);
    }

    /**
     * Flat parameter — no enum values, no element schema, no nested
     * properties. Retained so existing callers keep compiling and behaving
     * identically.
     */
    public ToolParameter(String name, String description, String type, boolean required) {
        this(name, description, type, required, List.of(), null, List.of());
    }

    /** A string parameter constrained to {@code values}. */
    public static ToolParameter ofEnum(String name, String description, boolean required,
                                       List<String> values) {
        return new ToolParameter(name, description, "string", required, values, null, List.of());
    }

    /** An array parameter whose elements are described by {@code items}. */
    public static ToolParameter ofArray(String name, String description, boolean required,
                                        ToolParameter items) {
        return new ToolParameter(name, description, "array", required, List.of(), items, List.of());
    }

    /** An object parameter with the given nested field schemas. */
    public static ToolParameter ofObject(String name, String description, boolean required,
                                         List<ToolParameter> properties) {
        return new ToolParameter(name, description, "object", required, List.of(), null, properties);
    }

    /** Whether this parameter constrains its value to a fixed set. */
    public boolean hasEnumValues() {
        return enumValues != null && !enumValues.isEmpty();
    }

    /** Whether this parameter carries nested field schemas. */
    public boolean hasProperties() {
        return properties != null && !properties.isEmpty();
    }

    /**
     * Map a Java class to a JSON Schema type string. Enums map to
     * {@code string} (their allowed values ride in {@link #enumValues}), and
     * arrays / collections map to {@code array} — before this they both
     * collapsed to {@code object}, which described neither.
     */
    public static String jsonSchemaType(Class<?> clazz) {
        if (clazz == String.class || clazz == CharSequence.class || clazz.isEnum()) {
            return "string";
        } else if (clazz == int.class || clazz == Integer.class
                || clazz == long.class || clazz == Long.class
                || clazz == short.class || clazz == Short.class
                || clazz == byte.class || clazz == Byte.class) {
            return "integer";
        } else if (clazz == float.class || clazz == Float.class
                || clazz == double.class || clazz == Double.class) {
            return "number";
        } else if (clazz == boolean.class || clazz == Boolean.class) {
            return "boolean";
        } else if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)) {
            return "array";
        } else {
            return "object";
        }
    }
}
