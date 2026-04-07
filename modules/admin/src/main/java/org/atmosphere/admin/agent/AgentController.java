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
package org.atmosphere.admin.agent;

import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read operations for {@code @Agent}-annotated endpoints. Discovers agents
 * by scanning the framework's handler registry for paths matching
 * {@code /atmosphere/agent/*}.
 *
 * <p>Agent metadata is extracted from the {@code @Agent} annotation on the
 * handler's backing class at runtime via reflection.</p>
 *
 * @since 4.0
 */
public final class AgentController {

    private static final Logger logger = LoggerFactory.getLogger(AgentController.class);
    private static final String AGENT_PATH_PREFIX = "/atmosphere/agent/";
    private static final String AGENT_ANNOTATION = "org.atmosphere.agent.annotation.Agent";
    private static final String COORDINATOR_ANNOTATION = "org.atmosphere.coordinator.annotation.Coordinator";

    private final AtmosphereFramework framework;

    public AgentController(AtmosphereFramework framework) {
        this.framework = framework;
    }

    /**
     * List all registered agents with metadata extracted from their
     * {@code @Agent} or {@code @Coordinator} annotation. Discovers both
     * non-headless agents (registered at {@code /atmosphere/agent/{name}})
     * and headless agents (only MCP/A2A sub-paths exist).
     */
    public List<Map<String, Object>> listAgents() {
        var handlers = framework.getAtmosphereHandlers();
        var result = new ArrayList<Map<String, Object>>();
        var seen = new java.util.HashSet<String>();

        for (var entry : handlers.entrySet()) {
            var path = entry.getKey();
            if (!path.startsWith(AGENT_PATH_PREFIX)) {
                continue;
            }

            // Determine the agent name from the path
            var suffix = path.substring(AGENT_PATH_PREFIX.length());
            String agentName;
            boolean headless = false;

            if (suffix.contains("/")) {
                // This is a sub-path like {name}/mcp or {name}/a2a
                agentName = suffix.substring(0, suffix.indexOf('/'));
                headless = !handlers.containsKey(AGENT_PATH_PREFIX + agentName);
            } else {
                // This is the base path: {name}
                agentName = suffix;
            }

            if (agentName.isEmpty() || !seen.add(agentName)) {
                continue;
            }

            var info = new LinkedHashMap<String, Object>();
            info.put("name", agentName);
            info.put("path", AGENT_PATH_PREFIX + agentName);

            // Get the handler for metadata extraction (prefer base, fallback to MCP)
            var baseWrapper = handlers.get(AGENT_PATH_PREFIX + agentName);
            var mcpWrapper = handlers.get(AGENT_PATH_PREFIX + agentName + "/mcp");
            var handler = baseWrapper != null
                    ? baseWrapper.atmosphereHandler() : null;

            if (handler != null) {
                info.put("handlerClass", handler.getClass().getSimpleName());
                extractAgentMetadata(handler, info);
            }

            // Override headless if detected from annotation
            if (headless) {
                info.put("headless", true);
            }

            // Detect protocols
            var protocols = new ArrayList<String>();
            if (handlers.containsKey(AGENT_PATH_PREFIX + agentName + "/a2a")
                    || handlers.containsKey("/atmosphere/a2a/" + agentName.replace("-agent", ""))) {
                protocols.add("a2a");
            }
            if (mcpWrapper != null) {
                protocols.add("mcp");
            }
            if (handlers.containsKey(AGENT_PATH_PREFIX + agentName + "/agui")) {
                protocols.add("agui");
            }
            info.put("protocols", protocols);

            result.add(info);
        }
        return result;
    }

    /**
     * Get detail for a specific agent by name.
     */
    public Optional<Map<String, Object>> getAgent(String name) {
        // Find this agent in the full list (uses the same discovery logic)
        return listAgents().stream()
                .filter(a -> name.equals(a.get("name")))
                .findFirst();
    }

    /**
     * List active sessions for a specific agent. Uses reflection to access
     * {@code AgentSessionRegistry} to avoid compile-time dependency.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSessions(String agentName) {
        try {
            var registryClass = Class.forName("org.atmosphere.agent.session.AgentSessionRegistry");
            var instanceMethod = registryClass.getMethod("instance");
            var registry = instanceMethod.invoke(null);
            var sessionsMethod = registryClass.getMethod("sessionsForAgent", String.class);
            var sessions = (List<?>) sessionsMethod.invoke(registry, agentName);

            var result = new ArrayList<Map<String, Object>>();
            for (var session : sessions) {
                var info = new LinkedHashMap<String, Object>();
                info.put("sessionId", invokeGetter(session, "sessionId"));
                info.put("agentName", invokeGetter(session, "agentName"));
                info.put("transport", invokeGetter(session, "transport"));
                info.put("startTime", String.valueOf(invokeGetter(session, "startTime")));
                info.put("lastActivity", String.valueOf(invokeGetter(session, "lastActivity")));
                info.put("messageCount", invokeGetter(session, "messageCount"));
                result.add(info);
            }
            return result;
        } catch (ClassNotFoundException e) {
            logger.trace("AgentSessionRegistry not on classpath", e);
            return List.of();
        } catch (Exception e) {
            logger.trace("Could not query agent sessions", e);
            return List.of();
        }
    }

    /**
     * Get the total active session count across all agents.
     */
    public int totalSessionCount() {
        try {
            var registryClass = Class.forName("org.atmosphere.agent.session.AgentSessionRegistry");
            var instanceMethod = registryClass.getMethod("instance");
            var registry = instanceMethod.invoke(null);
            var countMethod = registryClass.getMethod("totalSessionCount");
            return (int) countMethod.invoke(registry);
        } catch (Exception e) {
            logger.trace("Could not query total session count", e);
            return 0;
        }
    }

    private Object invokeGetter(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    /**
     * Extract metadata from the {@code @Agent} annotation via reflection.
     * This avoids a compile-time dependency on the agent module.
     */
    private void extractAgentMetadata(Object handler, Map<String, Object> info) {
        try {
            // The AgentHandler wraps the original annotated class. Try to find
            // the @Agent annotation on the handler itself or its delegate.
            var agentAnnotation = findAgentAnnotation(handler);
            if (agentAnnotation != null) {
                info.put("name", invokeAnnotationMethod(agentAnnotation, "name"));
                info.put("version", invokeAnnotationMethod(agentAnnotation, "version"));
                info.put("description", invokeAnnotationMethod(agentAnnotation, "description"));
                info.put("headless", invokeAnnotationMethod(agentAnnotation, "headless"));
            }
        } catch (Exception e) {
            logger.trace("Could not extract @Agent metadata", e);
        }
    }

    private Object findAgentAnnotation(Object handler) {
        // Check the handler class itself for @Agent or @Coordinator
        for (var annotation : handler.getClass().getAnnotations()) {
            var name = annotation.annotationType().getName();
            if (name.equals(AGENT_ANNOTATION) || name.equals(COORDINATOR_ANNOTATION)) {
                return annotation;
            }
        }
        // AgentHandler may hold a reference to the original annotated instance.
        // Try to find a field that holds an object with @Agent or @Coordinator.
        for (var field : handler.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                var value = field.get(handler);
                if (value != null) {
                    for (var annotation : value.getClass().getAnnotations()) {
                        var name = annotation.annotationType().getName();
                        if (name.equals(AGENT_ANNOTATION) || name.equals(COORDINATOR_ANNOTATION)) {
                            return annotation;
                        }
                    }
                }
            } catch (Exception e) {
                // skip inaccessible fields
            }
        }
        return null;
    }

    private Object invokeAnnotationMethod(Object annotation, String methodName) {
        try {
            var method = annotation.getClass().getMethod(methodName);
            return method.invoke(annotation);
        } catch (Exception e) {
            return null;
        }
    }
}
