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
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
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
    private final ApplicationContext context;

    public AtmosphereConsoleInfoEndpoint(AtmosphereProperties properties,
                                          AtmosphereFramework framework,
                                          ApplicationContext context) {
        this.properties = properties;
        this.framework = framework;
        this.context = context;
    }

    @GetMapping("/api/console/info")
    public Map<String, Object> info() {
        var result = new LinkedHashMap<String, Object>();
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
            // Dedicated origin for the MCP Apps sandbox proxy (SEP-1865), when a
            // deployer configured one. Only surfaced alongside a live MCP
            // endpoint and only when non-blank, so the console derives its own
            // dev origin otherwise (Runtime Truth — no empty config echoed back).
            var sandboxOrigin = properties.getMcpSandboxOrigin();
            if (sandboxOrigin != null && !sandboxOrigin.isBlank()) {
                result.put("mcpSandboxOrigin", sandboxOrigin.trim());
            }
        }
        // Runtime-resolved capability flags so the console only probes optional
        // endpoints that actually exist — avoids a 404 to /api/interactions or
        // /api/admin/verifier/summary (which the browser logs as a red error)
        // on samples that don't carry those modules. Bean presence is the
        // truthful runtime signal: InteractionsEndpoint is @ConditionalOnBean
        // (InteractionService) and the verifier summary returns 404 unless the
        // VerifierController bean is wired (Runtime Truth — Invariant #5).
        result.put("hasInteractions", hasBean("org.atmosphere.interactions.InteractionService"));
        result.put("hasVerifier", hasBean("org.atmosphere.admin.ai.VerifierController"));
        // Durable-run checkpoints: true only when a live CheckpointStore bean is
        // present, which is exactly the condition under which
        // AtmosphereCheckpointEndpoint maps /api/admin/checkpoints. Gating the
        // console tab on the bean (not the classpath) keeps the Checkpoints tab
        // off samples that carry no store, and off a 404 probe (Runtime Truth —
        // Invariant #5).
        result.put("hasCheckpoints", hasBean("org.atmosphere.checkpoint.CheckpointStore"));
        // Session tape: true only when a recorder is actually installed, not
        // merely on the classpath — so the Tape tab and its /api/admin/tape
        // reads appear only when the opt-in tape is live (Runtime Truth —
        // Invariant #5). Probed reflectively because atmosphere-ai is an
        // optional dependency: a direct TapeSupport.installed() call would
        // NoClassDefFoundError when info is requested on a sample that doesn't
        // carry the tape package.
        result.put("hasTape", hasInstalledTape());
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

    /**
     * Whether a Spring bean of the named type is present, without taking a
     * compile-time dependency on the optional module that declares it. Returns
     * {@code false} when the class is absent from the classpath (the module
     * isn't on it) so the capability is reported honestly as not present.
     */
    private boolean hasBean(String fqcn) {
        try {
            var type = ClassUtils.forName(fqcn, context.getClassLoader());
            return context.getBeanNamesForType(type, true, false).length > 0;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Whether a session-tape store is installed at runtime
     * ({@code TapeSupport.installed()}). Probed reflectively so this
     * always-active endpoint keeps no compile-time link to the optional
     * {@code atmosphere-ai} tape package — a direct call would throw
     * {@link NoClassDefFoundError} when info is requested on a sample that
     * doesn't carry atmosphere-ai. Absent package and absent store both read
     * as {@code false} (Runtime Truth — Invariant #5).
     */
    private boolean hasInstalledTape() {
        try {
            var type = ClassUtils.forName("org.atmosphere.ai.tape.TapeSupport",
                    context.getClassLoader());
            return Boolean.TRUE.equals(type.getMethod("installed").invoke(null));
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        } catch (ReflectiveOperationException e) {
            logger.trace("tape installed() probe failed", e);
            return false;
        }
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
