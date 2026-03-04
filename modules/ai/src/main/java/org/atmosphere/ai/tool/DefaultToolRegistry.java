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

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link ToolRegistry}. Thread-safe, supports
 * both annotation-driven and manual tool registration.
 */
public class DefaultToolRegistry implements ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private final ConcurrentHashMap<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    @Override
    public void register(ToolDefinition tool) {
        var existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Tool already registered: " + tool.name());
        }
        logger.info("Registered AI tool: {} — {}", tool.name(), tool.description());
    }

    @Override
    public void register(Object toolProvider) {
        var providerClass = toolProvider.getClass();
        for (var method : providerClass.getDeclaredMethods()) {
            var annotation = method.getAnnotation(AiTool.class);
            if (annotation != null) {
                var definition = buildFromMethod(toolProvider, method, annotation);
                register(definition);
            }
        }
    }

    @Override
    public Optional<ToolDefinition> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<ToolDefinition> getTools(Collection<String> names) {
        var result = new ArrayList<ToolDefinition>(names.size());
        for (var name : names) {
            var tool = tools.get(name);
            if (tool != null) {
                result.add(tool);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Collection<ToolDefinition> allTools() {
        return List.copyOf(tools.values());
    }

    @Override
    public boolean unregister(String name) {
        var removed = tools.remove(name) != null;
        if (removed) {
            logger.info("Unregistered AI tool: {}", name);
        }
        return removed;
    }

    @Override
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        var tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.failure(toolName, "Unknown tool: " + toolName);
        }
        try {
            var result = tool.executor().execute(arguments);
            var resultStr = result != null ? result.toString() : "null";
            return ToolResult.success(toolName, resultStr);
        } catch (Exception e) {
            logger.error("Tool execution failed: {}", toolName, e);
            return ToolResult.failure(toolName, e.getMessage());
        }
    }

    private ToolDefinition buildFromMethod(Object instance, Method method, AiTool annotation) {
        method.setAccessible(true);

        var parameters = new ArrayList<ToolParameter>();
        for (Parameter param : method.getParameters()) {
            var paramAnnotation = param.getAnnotation(Param.class);
            if (paramAnnotation != null) {
                parameters.add(new ToolParameter(
                        paramAnnotation.value(),
                        paramAnnotation.description(),
                        ToolParameter.jsonSchemaType(param.getType()),
                        paramAnnotation.required()
                ));
            } else {
                parameters.add(new ToolParameter(
                        param.getName(),
                        "",
                        ToolParameter.jsonSchemaType(param.getType()),
                        true
                ));
            }
        }

        var returnType = ToolParameter.jsonSchemaType(method.getReturnType());

        ToolExecutor executor = args -> {
            var methodParams = method.getParameters();
            var invokeArgs = new Object[methodParams.length];
            for (int i = 0; i < methodParams.length; i++) {
                var paramAnnotation = methodParams[i].getAnnotation(Param.class);
                var paramName = paramAnnotation != null
                        ? paramAnnotation.value()
                        : methodParams[i].getName();
                invokeArgs[i] = convertArg(args.get(paramName), methodParams[i].getType());
            }
            return method.invoke(instance, invokeArgs);
        };

        return new ToolDefinition(
                annotation.name(),
                annotation.description(),
                parameters,
                returnType,
                executor
        );
    }

    @SuppressWarnings("unchecked")
    private static Object convertArg(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        // Handle common conversions from JSON-parsed types
        var str = value.toString();
        if (targetType == String.class) {
            return str;
        } else if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(str);
        } else if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(str);
        } else if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(str);
        } else if (targetType == float.class || targetType == Float.class) {
            return Float.parseFloat(str);
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(str);
        }
        return value;
    }
}
