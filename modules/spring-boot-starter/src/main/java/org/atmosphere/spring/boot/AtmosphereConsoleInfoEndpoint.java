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
        var subtitle = properties.getConsoleSubtitle();
        if (subtitle == null || subtitle.isBlank()) {
            subtitle = "Runtime: " + detectRuntime();
        }
        result.put("subtitle", subtitle);
        result.put("endpoint", detectEndpoint());
        result.put("runtime", detectRuntime());
        return result;
    }

    private String detectRuntime() {
        try {
            var runtime = AgentRuntimeResolver.resolve();
            return runtime.name();
        } catch (Exception e) {
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
        var configuredPath = properties.getAi().getPath();
        try {
            var handlers = framework.getAtmosphereHandlers();
            // Look for @Agent handler (AgentHandler at /atmosphere/agent/*)
            for (var path : handlers.keySet()) {
                if (path.startsWith("/atmosphere/agent/") && !path.contains("/a2a") && !path.contains("/mcp")) {
                    return path;
                }
            }
        } catch (Exception e) {
            logger.debug("Framework not initialized yet, using configured default", e);
        }
        return configuredPath;
    }
}
