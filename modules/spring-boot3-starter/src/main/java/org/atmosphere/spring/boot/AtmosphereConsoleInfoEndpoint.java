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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.governance.memory.MemorySafetyConfig;
import org.atmosphere.ai.governance.rag.RagSafetyConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.util.PathTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;
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

    // Framework property published by the core harness preset installer
    // (modules/ai): a Map<String, String> of primitive → ACTIVE / INACTIVE(reason)
    // / CONVENTION. String-bridged so this controller keeps compiling against
    // atmosphere-ai versions that predate the preset classes.
    static final String HARNESS_RUNTIME_STATE_PROPERTY =
            "org.atmosphere.ai.harness.runtime-state";

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
        var endpoint = detectEndpoint();
        var mode = detectMode(endpoint);
        var subtitle = properties.getConsoleSubtitle();
        if (subtitle == null || subtitle.isBlank()) {
            // Mode-aware default, in parity with the Spring Boot 4 starter and
            // Quarkus: a broadcast endpoint (@ManagedService) does not inherit
            // the AI runtime label.
            subtitle = "broadcast".equals(mode)
                    ? "Multi-client broadcast chat"
                    : "Runtime: " + detectRuntime();
        }
        result.put("subtitle", subtitle);
        result.put("endpoint", endpoint);
        result.put("runtime", detectRuntime());
        // ai | broadcast, so the console renders honest empty-state copy; same
        // field and values as the Spring Boot 4 starter and Quarkus.
        result.put("mode", mode);
        // Wire transport the console client should use, in parity with the
        // Spring Boot 4 starter (AtmosphereConsoleInfoEndpoint#detectTransport).
        // Validated against the adapter set the console actually ships — an
        // unrecognized value is reported as "atmosphere" so a typo can never
        // make the console load a missing adapter (Runtime Truth — Inv #5).
        result.put("transport", detectTransport());
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
        // Harness preset: reported only when the core preset installer
        // published its per-primitive runtime states into the framework property
        // bag — i.e. the preset actually ran, not merely that it was configured
        // (Runtime Truth — Invariant #5).
        var harness = detectHarnessState();
        if (harness != null) {
            result.put("harness", harness);
        }
        // Session tape: true only when a recorder is actually installed at
        // runtime (not merely on the classpath) — the truthful signal that gates
        // the console Tape tab (Runtime Truth — Invariant #5). Probed reflectively
        // because atmosphere-ai is optional: a direct TapeSupport.installed() call
        // would NoClassDefFoundError on samples without the tape package.
        result.put("hasTape", hasInstalledTape());
        return result;
    }

    /**
     * Runtime-truth view of the harness preset: the per-primitive
     * ACTIVE / INACTIVE(reason) / CONVENTION states the core preset installer
     * published into the framework property bag. Returns {@code null} when the
     * preset never ran (property absent or empty), so the console omits the
     * section instead of echoing configuration intent.
     */
    private Map<String, String> detectHarnessState() {
        try {
            var cfg = framework.getAtmosphereConfig();
            if (cfg == null) {
                return null;
            }
            if (cfg.properties().get(HARNESS_RUNTIME_STATE_PROPERTY) instanceof Map<?, ?> states
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
            logger.debug("Harness runtime state not available", e);
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
            // This application's runtime: its framework's AI environment,
            // never another application's in the same JVM (Runtime Truth).
            var aiEnvironment = AiEnvironment.find(framework.getAtmosphereConfig())
                    .orElseGet(AiEnvironment::current);
            return aiEnvironment.runtimes().resolve().name();
        } catch (Exception e) {
            logger.debug("Could not resolve AgentRuntime", e);
            return "unknown";
        }
    }

    /**
     * The wire transport the console client should use, validated against the
     * set of transports the console actually ships an adapter for. Any value
     * outside {@code atmosphere | grpc | a2a | ag-ui} (including blank) is
     * reported as {@code atmosphere} so a typo can never make the console load
     * an adapter that doesn't exist and silently fail to connect (Runtime Truth
     * — Invariant #5: advertise only confirmed capability).
     */
    private String detectTransport() {
        var configured = properties.getConsoleTransport();
        if (configured == null) {
            return "atmosphere";
        }
        return switch (configured.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "grpc", "a2a", "ag-ui" -> configured.trim().toLowerCase(java.util.Locale.ROOT);
            default -> "atmosphere";
        };
    }

    /**
     * Resolve the handler of a path-template service ({@code /rooms/{room}})
     * that serves {@code endpoint}. Package-visible for unit testing.
     *
     * @return the wrapper whose template matches {@code endpoint}, or {@code null}
     */
    static <W> W templateWrapper(Map<String, W> handlers, String endpoint) {
        for (var entry : handlers.entrySet()) {
            if (!entry.getKey().contains("{")) {
                continue;
            }
            try {
                if (new PathTemplate(entry.getKey()).match(endpoint, new HashMap<>())) {
                    return entry.getValue();
                }
            } catch (IllegalArgumentException e) {
                logger.trace("Not a path template: {}", entry.getKey(), e);
            }
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
            var handlers = framework.getAtmosphereHandlers();
            var wrapper = handlers.get(endpoint);
            if (wrapper == null) {
                // A path-template service (/rooms/{room}) registers the concrete
                // path only on its first request: classify by the template it
                // already serves, not by whether a client has connected yet.
                wrapper = templateWrapper(handlers, endpoint);
            }
            if (wrapper == null) {
                return "ai";
            }
            var handlerClassName = wrapper.atmosphereHandler().getClass().getName();
            // AiEndpointHandler (modules/ai), AgentHandler (modules/agent),
            // protocol bridges over agents (A2aHandler, AgUiHandler), and any
            // handler whose package is under org.atmosphere.{ai,agent,
            // coordinator,a2a,agui} count as AI-shaped. Everything else
            // (notably ManagedAtmosphereHandler from modules/cpr) is
            // broadcast-shaped.
            if (handlerClassName.startsWith("org.atmosphere.ai.")
                    || handlerClassName.startsWith("org.atmosphere.agent.")
                    || handlerClassName.startsWith("org.atmosphere.coordinator.")
                    || handlerClassName.startsWith("org.atmosphere.a2a.")
                    || handlerClassName.startsWith("org.atmosphere.agui.")) {
                return "ai";
            }
            return "broadcast";
        } catch (Exception e) {
            logger.debug("Could not classify handler mode for {}", endpoint, e);
            return "ai";
        }
    }

    /**
     * Auto-detects the best endpoint for the console, in the same order as the
     * Spring Boot 4 starter: {@code atmosphere.ai.path} when it names an agent
     * path or a registered handler, then an {@code @Agent} endpoint (paths
     * starting with {@code /atmosphere/agent/}), then any other registered
     * {@code /atmosphere/*} handler outside the admin surface (e.g. a
     * {@code @ManagedService}), and finally the configured default.
     */
    private String detectEndpoint() {
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

    /**
     * Whether a tape recorder is actually installed at runtime — probed
     * reflectively so the optional {@code atmosphere-ai} tape package staying off
     * the classpath (non-AI apps) does not force-load it here.
     */
    private boolean hasInstalledTape() {
        try {
            var type = ClassUtils.forName("org.atmosphere.ai.tape.TapeSupport",
                    getClass().getClassLoader());
            return Boolean.TRUE.equals(type.getMethod("installed").invoke(null));
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        } catch (ReflectiveOperationException e) {
            logger.trace("tape installed() probe failed", e);
            return false;
        }
    }
}
