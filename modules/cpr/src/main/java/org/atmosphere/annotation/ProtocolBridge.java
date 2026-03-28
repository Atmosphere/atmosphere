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
package org.atmosphere.annotation;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.ArrayList;

/**
 * Reflective bridge that registers protocol endpoints (MCP, A2A) for
 * {@code @ManagedService} classes without requiring compile-time dependencies
 * on the protocol modules.
 *
 * <p>When {@code atmosphere-mcp} or {@code atmosphere-a2a} is on the classpath,
 * this bridge scans the managed service instance for protocol annotations
 * ({@code @McpTool}, {@code @AgentSkill}, etc.) and registers the corresponding
 * handlers alongside the main WebSocket/SSE handler.</p>
 *
 * <p>All protocol classes are accessed via reflection to avoid circular
 * dependencies — {@code atmosphere-runtime} (cpr) cannot depend on
 * {@code atmosphere-mcp} or {@code atmosphere-a2a}.</p>
 *
 * @author Jeanfrancois Arcand
 */
public final class ProtocolBridge {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolBridge.class);

    private ProtocolBridge() {
    }

    /**
     * Attempts to register an MCP endpoint for the given instance.
     * Silently returns if {@code atmosphere-mcp} is not on the classpath
     * or the instance has no {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt} methods.
     *
     * @param framework the atmosphere framework
     * @param instance  the {@code @ManagedService} instance to scan
     * @param basePath  the base path of the managed service (e.g. {@code "/chat"})
     */
    @SuppressWarnings("unchecked")
    public static void tryRegisterMcp(AtmosphereFramework framework, Object instance, String basePath) {
        try {
            var mcpToolClass = (Class<? extends Annotation>) Class.forName("org.atmosphere.mcp.annotation.McpTool");
            var mcpResourceClass = (Class<? extends Annotation>) Class.forName("org.atmosphere.mcp.annotation.McpResource");
            var mcpPromptClass = (Class<? extends Annotation>) Class.forName("org.atmosphere.mcp.annotation.McpPrompt");

            boolean hasMcpMethods = false;
            for (var m : instance.getClass().getMethods()) {
                if (m.isAnnotationPresent(mcpToolClass) || m.isAnnotationPresent(mcpResourceClass)
                    || m.isAnnotationPresent(mcpPromptClass)) {
                    hasMcpMethods = true;
                    break;
                }
            }
            if (!hasMcpMethods) {
                return;
            }

            // Create McpRegistry and scan the instance
            var registryClass = Class.forName("org.atmosphere.mcp.registry.McpRegistry");
            var registry = registryClass.getDeclaredConstructor().newInstance();
            registryClass.getMethod("scan", Object.class).invoke(registry, instance);

            // Create McpProtocolHandler(name, version, registry, config)
            var phClass = Class.forName("org.atmosphere.mcp.runtime.McpProtocolHandler");
            var ph = phClass.getConstructor(String.class, String.class, registryClass, AtmosphereConfig.class)
                    .newInstance("managed-service", "1.0.0", registry, framework.getAtmosphereConfig());

            // Create McpHandler(protocolHandler)
            var handlerClass = Class.forName("org.atmosphere.mcp.runtime.McpHandler");
            var handler = handlerClass.getConstructor(phClass).newInstance(ph);

            var mcpPath = basePath + "/mcp";
            framework.addAtmosphereHandler(mcpPath, (AtmosphereHandler) handler, new ArrayList<>());
            logger.info("MCP endpoint registered at {} for @ManagedService {}", mcpPath, basePath);
        } catch (ClassNotFoundException e) {
            // atmosphere-mcp not on classpath — silently skip
        } catch (Exception e) {
            logger.warn("Failed to register MCP bridge for @ManagedService at {}: {}", basePath, e.getMessage());
        }
    }

    /**
     * Attempts to register an A2A endpoint for the given instance.
     * Silently returns if {@code atmosphere-a2a} is not on the classpath
     * or the instance has no {@code @AgentSkill} + {@code @AgentSkillHandler} methods.
     *
     * @param framework the atmosphere framework
     * @param instance  the {@code @ManagedService} instance to scan
     * @param basePath  the base path of the managed service (e.g. {@code "/chat"})
     */
    @SuppressWarnings("unchecked")
    public static void tryRegisterA2a(AtmosphereFramework framework, Object instance, String basePath) {
        try {
            var skillClass = (Class<? extends Annotation>) Class.forName("org.atmosphere.a2a.annotation.AgentSkill");
            var handlerAnnClass = (Class<? extends Annotation>) Class.forName("org.atmosphere.a2a.annotation.AgentSkillHandler");

            boolean hasSkills = false;
            for (var m : instance.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(skillClass) && m.isAnnotationPresent(handlerAnnClass)) {
                    hasSkills = true;
                    break;
                }
            }
            if (!hasSkills) {
                return;
            }

            // Create A2aRegistry and scan
            var registryClass = Class.forName("org.atmosphere.a2a.registry.A2aRegistry");
            var registry = registryClass.getDeclaredConstructor().newInstance();
            registryClass.getMethod("scan", Object.class).invoke(registry, instance);

            // Build AgentCard
            var buildCardMethod = registryClass.getMethod("buildAgentCard",
                    String.class, String.class, String.class, String.class);
            var a2aPath = basePath + "/a2a";
            var card = buildCardMethod.invoke(registry, "managed-service",
                    "A2A bridge for " + basePath, "1.0.0", a2aPath);

            // Create TaskManager
            var tmClass = Class.forName("org.atmosphere.a2a.runtime.TaskManager");
            var tm = tmClass.getDeclaredConstructor().newInstance();

            // Create A2aProtocolHandler(registry, taskManager, card)
            var cardClass = Class.forName("org.atmosphere.a2a.types.AgentCard");
            var phClass = Class.forName("org.atmosphere.a2a.runtime.A2aProtocolHandler");
            var ph = phClass.getConstructor(registryClass, tmClass, cardClass)
                    .newInstance(registry, tm, card);

            // Create A2aHandler(protocolHandler)
            var a2aHandlerClass = Class.forName("org.atmosphere.a2a.runtime.A2aHandler");
            var handler = a2aHandlerClass.getConstructor(phClass).newInstance(ph);

            framework.addAtmosphereHandler(a2aPath, (AtmosphereHandler) handler, new ArrayList<>());
            logger.info("A2A endpoint registered at {} for @ManagedService {}", a2aPath, basePath);
        } catch (ClassNotFoundException e) {
            // atmosphere-a2a not on classpath — silently skip
        } catch (Exception e) {
            logger.warn("Failed to register A2A bridge for @ManagedService at {}: {}", basePath, e.getMessage());
        }
    }
}
