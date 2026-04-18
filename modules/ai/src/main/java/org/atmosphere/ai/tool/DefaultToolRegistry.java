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

        // Framework-scoped parameter types don't appear in the JSON schema —
        // the LLM must never be asked for them. DefaultToolRegistry excludes
        // any method parameter whose declared type is likely to be supplied by
        // an injectables map at dispatch time. The actual resolution happens
        // in the executor below via assignable-from matches on the same map.
        var parameters = new ArrayList<ToolParameter>();
        for (Parameter param : method.getParameters()) {
            if (isFrameworkInjectableType(param.getType())) {
                continue;
            }
            var paramAnnotation = param.getAnnotation(Param.class);
            if (paramAnnotation != null) {
                parameters.add(new ToolParameter(
                        paramAnnotation.value(),
                        paramAnnotation.description(),
                        ToolParameter.jsonSchemaType(param.getType()),
                        paramAnnotation.required()
                ));
            } else {
                var name = param.getName();
                if (name.matches("arg\\d+")) {
                    logger.warn("Tool method '{}' parameter '{}' has synthetic name — "
                            + "add @Param annotation or compile with -parameters flag",
                            method.getName(), name);
                }
                parameters.add(new ToolParameter(
                        name,
                        "",
                        ToolParameter.jsonSchemaType(param.getType()),
                        true
                ));
            }
        }

        var returnType = ToolParameter.jsonSchemaType(method.getReturnType());

        ToolExecutor executor = new ToolExecutor() {
            @Override
            public Object execute(Map<String, Object> args) throws Exception {
                return execute(args, Map.of());
            }

            @Override
            public Object execute(Map<String, Object> args,
                                  Map<Class<?>, Object> injectables) throws Exception {
                var methodParams = method.getParameters();
                var invokeArgs = new Object[methodParams.length];
                var scope = injectables != null ? injectables : Map.<Class<?>, Object>of();
                for (int i = 0; i < methodParams.length; i++) {
                    var p = methodParams[i];
                    var injected = resolveInjectable(p.getType(), scope);
                    if (injected.present()) {
                        invokeArgs[i] = injected.value();
                        continue;
                    }
                    var paramAnnotation = p.getAnnotation(Param.class);
                    var paramName = paramAnnotation != null
                            ? paramAnnotation.value()
                            : p.getName();
                    invokeArgs[i] = convertArg(args.get(paramName), p.getType());
                }
                return method.invoke(instance, invokeArgs);
            }
        };

        // Check for @RequiresApproval on the method
        var approvalAnnotation = method.getAnnotation(
                org.atmosphere.ai.annotation.RequiresApproval.class);
        String approvalMessage = null;
        long approvalTimeout = 0;
        if (approvalAnnotation != null) {
            approvalMessage = approvalAnnotation.value();
            approvalTimeout = approvalAnnotation.timeoutSeconds();
        }

        return new ToolDefinition(
                annotation.name(),
                annotation.description(),
                parameters,
                returnType,
                executor,
                approvalMessage,
                approvalTimeout
        );
    }

    /**
     * Result of an injectable lookup. A sentinel so {@code null} remains a
     * legal injected value distinct from "no match".
     */
    private record Injected(boolean present, Object value) {
        private static final Injected ABSENT = new Injected(false, null);
        static Injected of(Object v) { return new Injected(true, v); }
    }

    /**
     * Resolve {@code type} from the injectables map using
     * {@code isAssignableFrom} so a method can declare an interface
     * ({@code StreamingSession}) and receive the concrete implementation.
     *
     * <p>Exact key match is tried first (fastest path); then we scan the
     * entries for the first assignable type. The scan is linear on a map
     * that typically has fewer than ten entries, so this is cheap.</p>
     */
    private static Injected resolveInjectable(Class<?> type,
                                              Map<Class<?>, Object> scope) {
        if (scope.containsKey(type)) {
            return Injected.of(scope.get(type));
        }
        for (var entry : scope.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) {
                return Injected.of(entry.getValue());
            }
        }
        return Injected.ABSENT;
    }

    /**
     * Pre-registration check: is {@code type} likely to be supplied via the
     * framework injectables map at dispatch time? Kept conservative — we
     * exclude a parameter from the JSON schema only for types the framework
     * is known to inject. User domain types still get schema parameters.
     *
     * <p>Matched via class name so {@code atmosphere-ai} keeps its
     * independence from {@code atmosphere-agent} and
     * {@code atmosphere-coordinator} (which declare {@code AgentFleet},
     * {@code AgentIdentity}, etc.) — no inverted module dependencies.</p>
     */
    private static boolean isFrameworkInjectableType(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) {
            return false;
        }
        var name = type.getName();
        return name.equals("org.atmosphere.ai.StreamingSession")
                || name.equals("org.atmosphere.ai.AiStreamingSession")
                || name.equals("org.atmosphere.cpr.AtmosphereResource")
                || name.equals("org.atmosphere.coordinator.fleet.AgentFleet")
                || name.equals("org.atmosphere.ai.identity.AgentIdentity")
                || name.equals("org.atmosphere.ai.state.AgentState")
                || name.equals("org.atmosphere.ai.workspace.AgentWorkspace");
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
