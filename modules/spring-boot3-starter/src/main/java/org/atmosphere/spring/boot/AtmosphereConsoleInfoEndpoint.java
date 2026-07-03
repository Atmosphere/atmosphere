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
import org.atmosphere.ai.governance.memory.MemorySafetyConfig;
import org.atmosphere.ai.governance.rag.RagSafetyConfig;
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

    // Framework property published by the core deep-agent preset installer
    // (modules/ai): a Map<String, String> of primitive → ACTIVE / INACTIVE(reason)
    // / CONVENTION. String-bridged so this controller keeps compiling against
    // atmosphere-ai versions that predate the preset classes.
    static final String DEEP_AGENT_RUNTIME_STATE_PROPERTY =
            "org.atmosphere.ai.deep-agent.runtime-state";

    private final AtmosphereProperties properties;
    private final AtmosphereFramework framework;

    public AtmosphereConsoleInfoEndpoint(AtmosphereProperties properties,
                                          AtmosphereFramework framework) {
        this.properties = properties;
        this.framework = framework;
    }

    @GetMapping("/api/console/info")
    public Map<String, Object> info() {
        var result = new LinkedHashMap<String, Object>();
        var subtitle = properties.getConsoleSubtitle();
        if (subtitle == null || subtitle.isBlank()) {
            subtitle = "Runtime: " + detectRuntime();
        }
        result.put("subtitle", subtitle);
        result.put("endpoint", detectEndpoint());
        result.put("runtime", detectRuntime());
        // RAG injection-safety: reported only when a ContextProvider was actually
        // wrapped at endpoint registration (Runtime Truth — Invariant #5), with
        // the effective classifier tier after any runtime-absent downgrade.
        var ragSafety = detectRagSafety();
        if (ragSafety != null) {
            result.put("ragSafety", ragSafety);
        }
        // Long-term-memory injection-safety: reported only when MemorySafetyConfig
        // published an active screen into the framework property bag (Runtime Truth
        // — Invariant #5), with the effective tier after any runtime-absent downgrade.
        var memorySafety = detectMemorySafety();
        if (memorySafety != null) {
            result.put("memorySafety", memorySafety);
        }
        // Deep-agent preset: reported only when the core preset installer
        // published its per-primitive runtime states into the framework property
        // bag — i.e. the preset actually ran, not merely that it was configured
        // (Runtime Truth — Invariant #5).
        var deepAgent = detectDeepAgentState();
        if (deepAgent != null) {
            result.put("deepAgent", deepAgent);
        }
        return result;
    }

    /**
     * Runtime-truth view of the deep-agent preset: the per-primitive
     * ACTIVE / INACTIVE(reason) / CONVENTION states the core preset installer
     * published into the framework property bag. Returns {@code null} when the
     * preset never ran (property absent or empty), so the console omits the
     * section instead of echoing configuration intent.
     */
    private Map<String, String> detectDeepAgentState() {
        try {
            var cfg = framework.getAtmosphereConfig();
            if (cfg == null) {
                return null;
            }
            if (cfg.properties().get(DEEP_AGENT_RUNTIME_STATE_PROPERTY) instanceof Map<?, ?> states
                    && !states.isEmpty()) {
                var map = new LinkedHashMap<String, String>();
                for (var e : states.entrySet()) {
                    if (e.getKey() instanceof String key && e.getValue() instanceof String value) {
                        map.put(key, value);
                    }
                }
                return map.isEmpty() ? null : map;
            }
        } catch (Exception e) {
            logger.debug("Deep-agent runtime state not available", e);
        }
        return null;
    }

    /**
     * Runtime-truth view of the RAG injection-safety screen. Present only when
     * {@code RagSafetyConfig} published an active state into the framework
     * property bag (i.e. at least one {@code ContextProvider} was wrapped), and
     * the reported tier is the effective one after any runtime-absent downgrade.
     * Class-guarded so the console keeps loading when {@code atmosphere-ai} is
     * absent (the {@code instanceof} would otherwise raise NoClassDefFoundError).
     */
    private Map<String, Object> detectRagSafety() {
        try {
            var cfg = framework.getAtmosphereConfig();
            if (cfg == null) {
                return null;
            }
            var state = cfg.properties().get(RagSafetyConfig.RUNTIME_STATE_PROPERTY);
            if (state instanceof RagSafetyConfig.RagSafetyRuntimeState s && s.active()) {
                var map = new LinkedHashMap<String, Object>();
                map.put("active", true);
                map.put("tier", s.tier());
                map.put("breach", s.breach());
                return map;
            }
        } catch (LinkageError | Exception e) {
            logger.debug("RAG injection-safety state not available", e);
        }
        return null;
    }

    /**
     * Runtime-truth view of the long-term-memory injection-safety screen. Present
     * only when {@code MemorySafetyConfig} published an active state into the
     * framework property bag, with the effective tier after any runtime-absent
     * downgrade. Class-guarded so the console keeps loading when
     * {@code atmosphere-ai} is absent.
     */
    private Map<String, Object> detectMemorySafety() {
        try {
            var cfg = framework.getAtmosphereConfig();
            if (cfg == null) {
                return null;
            }
            var state = cfg.properties().get(MemorySafetyConfig.RUNTIME_STATE_PROPERTY);
            if (state instanceof MemorySafetyConfig.MemorySafetyRuntimeState s && s.active()) {
                var map = new LinkedHashMap<String, Object>();
                map.put("active", true);
                map.put("tier", s.tier());
                map.put("breach", s.breach());
                return map;
            }
        } catch (LinkageError | Exception e) {
            logger.debug("Memory injection-safety state not available", e);
        }
        return null;
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
