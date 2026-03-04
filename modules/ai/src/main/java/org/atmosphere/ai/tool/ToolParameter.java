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

/**
 * Describes a single parameter of an AI tool.
 *
 * @param name        parameter name as exposed to the model
 * @param description human-readable description
 * @param type        JSON Schema type (string, integer, number, boolean, object, array)
 * @param required    whether the model must provide this parameter
 */
public record ToolParameter(
        String name,
        String description,
        String type,
        boolean required
) {
    /**
     * Map a Java class to a JSON Schema type string.
     */
    public static String jsonSchemaType(Class<?> clazz) {
        if (clazz == String.class || clazz == CharSequence.class) {
            return "string";
        } else if (clazz == int.class || clazz == Integer.class
                || clazz == long.class || clazz == Long.class) {
            return "integer";
        } else if (clazz == float.class || clazz == Float.class
                || clazz == double.class || clazz == Double.class) {
            return "number";
        } else if (clazz == boolean.class || clazz == Boolean.class) {
            return "boolean";
        } else {
            return "object";
        }
    }
}
