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
package org.atmosphere.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Reusable JSON-RPC parameter binding for protocol handlers. Binds JSON arguments
 * to method parameters, resolving injectable framework types and converting primitives.
 *
 * @since 4.0.8
 */
public final class ParameterBinder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<Class<?>> injectableTypes;
    private final BiFunction<Class<?>, String, Object> injectableResolver;

    /**
     * @param injectableTypes    set of types that should be injected rather than bound from JSON
     * @param injectableResolver resolver function: (type, topic) → injected value
     */
    public ParameterBinder(Set<Class<?>> injectableTypes,
                           BiFunction<Class<?>, String, Object> injectableResolver) {
        this.injectableTypes = injectableTypes;
        this.injectableResolver = injectableResolver;
    }

    /**
     * Parameter metadata record used for binding.
     */
    public record ParamInfo(String name, String description, boolean required, Class<?> type) {}

    /**
     * Bind JSON arguments to a reflective method's parameters.
     */
    public Object[] bindArguments(Method method, List<ParamInfo> paramEntries,
                                  JsonNode arguments) {
        var methodParams = method.getParameters();
        var args = new Object[methodParams.length];
        int paramIdx = 0;

        String topic = null;
        if (arguments != null && arguments.has("topic")) {
            topic = arguments.get("topic").asText();
        }

        for (int i = 0; i < methodParams.length; i++) {
            var type = methodParams[i].getType();
            if (isInjectable(type)) {
                args[i] = injectableResolver.apply(type, topic);
            } else if (paramIdx < paramEntries.size()) {
                var param = paramEntries.get(paramIdx);
                if (arguments != null && arguments.has(param.name())) {
                    args[i] = convertParam(arguments.get(param.name()), param.type());
                } else if (param.required()) {
                    throw new IllegalArgumentException(
                            "Missing required parameter: " + param.name());
                } else {
                    args[i] = defaultValue(param.type());
                }
                paramIdx++;
            }
        }
        return args;
    }

    /**
     * Bind JSON arguments to a Map for dynamic (lambda-based) handlers.
     */
    public Map<String, Object> bindArgumentsAsMap(List<ParamInfo> paramEntries,
                                                   JsonNode arguments) {
        var map = new LinkedHashMap<String, Object>();
        for (var param : paramEntries) {
            if (arguments != null && arguments.has(param.name())) {
                map.put(param.name(), convertParam(arguments.get(param.name()), param.type()));
            } else if (param.required()) {
                throw new IllegalArgumentException(
                        "Missing required parameter: " + param.name());
            } else {
                map.put(param.name(), defaultValue(param.type()));
            }
        }
        if (arguments != null) {
            var it = arguments.fields();
            while (it.hasNext()) {
                var field = it.next();
                map.putIfAbsent(field.getKey(), field.getValue().asText());
            }
        }
        return map;
    }

    /** Check if a type is injectable. */
    public boolean isInjectable(Class<?> type) {
        for (var injectable : injectableTypes) {
            if (injectable.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    /** Convert a JSON node to the target type. */
    public static Object convertParam(JsonNode node, Class<?> type) {
        if (node == null || node.isNull()) return defaultValue(type);
        if (type == String.class) return node.asText();
        if (type == int.class || type == Integer.class) return node.asInt();
        if (type == long.class || type == Long.class) return node.asLong();
        if (type == double.class || type == Double.class) return node.asDouble();
        if (type == float.class || type == Float.class) return (float) node.asDouble();
        if (type == boolean.class || type == Boolean.class) return node.asBoolean();
        return MAPPER.convertValue(node, type);
    }

    /** Return the default value for a primitive type. */
    public static Object defaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        return null;
    }
}
