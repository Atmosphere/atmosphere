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
package org.atmosphere.spring.boot;

import java.util.LinkedHashMap;
import java.util.Map;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auto-configured REST endpoint providing metadata for the Atmosphere AI Console.
 * Auto-detects {@code @Agent} endpoints — if one is registered, the console
 * connects to it instead of the default {@code /atmosphere/ai-chat}.
 */
@AutoConfiguration
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereConsoleInfoEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereConsoleInfoEndpoint.class);

    private final AtmosphereProperties properties;
    private final AtmosphereFramework framework;

    public AtmosphereConsoleInfoEndpoint(AtmosphereProperties properties,
                                          AtmosphereFramework framework) {
        this.properties = properties;
        this.framework = framework;
    }

    @GetMapping("/api/console/info")
    public Map<String, String> info() {
        var result = new LinkedHashMap<String, String>();
        var endpoint = detectEndpoint();
        var mode = detectMode(endpoint);
        var subtitle = properties.getConsoleSubtitle();
        if (subtitle == null || subtitle.isBlank()) {
            // Mode-aware default so broadcast samples (mcp-server, otel-chat,
            // anything mounting @ManagedService at /atmosphere/ai-chat) don't
            // inherit the AI runtime label, which would be honest about the
            // bundled console but not about the sample's actual shape.
            subtitle = "broadcast".equals(mode)
                    ? "Multi-client broadcast chat"
                    : "Runtime: " + detectRuntime();
        }
        result.put("subtitle", subtitle);
        result.put("endpoint", endpoint);
        result.put("runtime", detectRuntime());
        result.put("mode", mode);
        // Report the live MCP endpoint (if any) so the console can host MCP Apps
        // (SEP-1865) against it. Omitted when no MCP handler is registered, so
        // the console only surfaces the Apps tab when there's truly one to talk
        // to (Runtime Truth).
        var mcpEndpoint = detectMcpEndpoint();
        if (mcpEndpoint != null) {
            result.put("mcpEndpoint", mcpEndpoint);
        }
        return result;
    }

    /**
     * The path of the registered MCP endpoint ({@code McpHandler}), or
     * {@code null} when none is registered. Class-name matched so this
     * controller keeps no compile-time dependency on {@code modules/mcp}.
     */
    private String detectMcpEndpoint() {
        try {
            for (var e : framework.getAtmosphereHandlers().entrySet()) {
                if ("org.atmosphere.mcp.runtime.McpHandler"
                        .equals(e.getValue().atmosphereHandler().getClass().getName())) {
                    return e.getKey();
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not detect MCP endpoint", ex);
        }
        return null;
    }

    /**
     * Reports whether the handler registered at {@code endpoint} is an
     * AI-shaped handler ({@code AiEndpointHandler} / {@code AgentHandler})
     * or a plain broadcast handler (e.g. {@code @ManagedService} →
     * {@code ManagedAtmosphereHandler}). The frontend uses this to render
     * honest empty-state copy — "Type a message below to begin chatting
     * with the AI assistant" is only accurate when an assistant exists.
     *
     * <p>Detection is class-name based so this controller doesn't drag in
     * a hard compile-time dep on {@code modules/ai} or {@code modules/agent}
     * — the AI Console must keep loading even when those JARs are absent.</p>
     */
    private String detectMode(String endpoint) {
        if (endpoint == null) {
            return "ai";
        }
        try {
            var wrapper = framework.getAtmosphereHandlers().get(endpoint);
            if (wrapper == null) {
                return "ai";
            }
            var handlerClassName = wrapper.atmosphereHandler().getClass().getName();
            // AiEndpointHandler (modules/ai), AgentHandler (modules/agent),
            // and any handler whose package is under org.atmosphere.{ai,agent,
            // coordinator} count as AI-shaped. Everything else (notably
            // ManagedAtmosphereHandler from modules/cpr) is broadcast-shaped.
            if (handlerClassName.startsWith("org.atmosphere.ai.")
                    || handlerClassName.startsWith("org.atmosphere.agent.")
                    || handlerClassName.startsWith("org.atmosphere.coordinator.")) {
                return "ai";
            }
            return "broadcast";
        } catch (Exception e) {
            logger.debug("Could not classify handler mode for {}", endpoint, e);
            return "ai";
        }
    }

    private String detectRuntime() {
        // Catch LinkageError too — atmosphere-ai may be absent (NoClassDefFoundError
        // on AgentRuntimeResolver), which would otherwise turn /api/console/info into a 500.
        try {
            var runtime = AgentRuntimeResolver.resolve();
            return runtime.name();
        } catch (LinkageError | Exception e) {
            logger.debug("Could not resolve AgentRuntime", e);
            return "unknown";
        }
    }

    /**
     * Auto-detects the best endpoint for the console. Prefers {@code @Agent}
     * endpoints (paths starting with {@code /atmosphere/agent/}) over the
     * default {@code /atmosphere/ai-chat}.
     */
    private String detectEndpoint() {
        // Explicit console endpoint override takes priority
        var consoleEndpoint = properties.getConsoleEndpoint();
        if (consoleEndpoint != null && !consoleEndpoint.isBlank()) {
            return consoleEndpoint;
        }
        var configuredPath = properties.getAi().getPath();
        // If explicitly configured to an agent path, respect it
        if (configuredPath != null && configuredPath.startsWith("/atmosphere/agent/")) {
            return configuredPath;
        }
        try {
            var handlers = framework.getAtmosphereHandlers();
            // Prefer the configured path when it corresponds to a registered handler.
            // Avoids picking the "wrong" @AiEndpoint in samples that declare multiple
            // (e.g. a primary chat endpoint + a demo structured-output endpoint).
            if (configuredPath != null && handlers.containsKey(configuredPath)) {
                return configuredPath;
            }
            // Look for @Agent handler (AgentHandler at /atmosphere/agent/*)
            for (var path : handlers.keySet()) {
                if (path.startsWith("/atmosphere/agent/") && !path.contains("/a2a") && !path.contains("/mcp")) {
                    return path;
                }
            }
            // Fall back to any registered handler (e.g. @ManagedService)
            for (var path : handlers.keySet()) {
                if (path.startsWith("/atmosphere/") && !path.contains("/admin")) {
                    return path;
                }
            }
        } catch (Exception e) {
            logger.debug("Framework not initialized yet, using configured default", e);
        }
        return configuredPath;
    }
}
