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
package org.atmosphere.quarkus.runtime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus parity for {@code AtmosphereConsoleInfoEndpoint} from the Spring Boot
 * starter — serves {@code GET /api/console/info} so the bundled Atmosphere
 * Console UI gets the same {@code subtitle / endpoint / runtime / mode}
 * payload regardless of the host runtime.
 *
 * <p>{@code mode} auto-detection mirrors the Spring path: classify the handler
 * registered at the resolved console endpoint by package prefix
 * ({@code org.atmosphere.{ai,agent,coordinator}.*} → {@code "ai"}; everything
 * else, including {@code ManagedAtmosphereHandler}, → {@code "broadcast"}).
 * The check is class-name based so this servlet doesn't drag in a hard
 * compile-time dep on {@code modules/ai} or {@code modules/agent} — the
 * Console must keep loading even when those JARs are absent.</p>
 *
 * <p>Init params (all optional):</p>
 * <ul>
 *   <li>{@code consoleSubtitle} — overrides the default mode-aware subtitle</li>
 *   <li>{@code consoleEndpoint} — pins the endpoint the Console connects to;
 *       when blank, falls back to auto-detection over the framework's handler
 *       map (prefer {@code /atmosphere/agent/*} over generic {@code /atmosphere/*})</li>
 * </ul>
 *
 * <p>JSON is hand-rolled to avoid pulling Jackson into the runtime classpath
 * (the Quarkus extension's runtime POM is intentionally minimal).</p>
 */
public class AtmosphereConsoleInfoServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereConsoleInfoServlet.class);

    public static final String CONSOLE_SUBTITLE_PARAM = "consoleSubtitle";
    public static final String CONSOLE_ENDPOINT_PARAM = "consoleEndpoint";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        var subtitle = orDefault(getInitParameter(CONSOLE_SUBTITLE_PARAM), "");
        var consoleEndpoint = orDefault(getInitParameter(CONSOLE_ENDPOINT_PARAM), "");
        var framework = LazyAtmosphereConfigurator.getFramework();

        var endpoint = detectEndpoint(framework, consoleEndpoint);
        var mode = detectMode(framework, endpoint);
        var runtime = detectRuntime();

        if (subtitle.isBlank()) {
            // Mode-aware default: broadcast samples don't borrow the AI runtime label.
            subtitle = "broadcast".equals(mode) ? "Multi-client broadcast chat" : "Runtime: " + runtime;
        }

        var payload = new LinkedHashMap<String, Object>();
        payload.put("subtitle", subtitle);
        payload.put("endpoint", endpoint);
        payload.put("runtime", runtime);
        payload.put("mode", mode);
        // Parity with the Spring starter's /api/console/info capability flags so
        // the shared console gates its optional tabs without a 404-producing
        // probe. The Quarkus extension does not bundle the interactions or
        // admin/verifier REST planes, so both are honestly false here.
        payload.put("hasInteractions", Boolean.FALSE);
        payload.put("hasVerifier", Boolean.FALSE);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        try (var w = resp.getWriter()) {
            w.write(toJson(payload));
        }
    }

    /** Default {@code @AiEndpoint} path when {@code atmosphere-ai} auto-registers one. */
    private static final String DEFAULT_AI_ENDPOINT = "/atmosphere/ai-chat";

    private static String detectEndpoint(AtmosphereFramework framework, String configuredOverride) {
        if (configuredOverride != null && !configuredOverride.isBlank()) {
            return configuredOverride;
        }
        if (framework == null) {
            return DEFAULT_AI_ENDPOINT;
        }
        try {
            var handlers = framework.getAtmosphereHandlers();
            // Match the Spring starter precedence (AtmosphereConsoleInfoEndpoint#detectEndpoint):
            //   1. The default @AiEndpoint path if one is actually registered.
            //   2. Any @Agent / @Coordinator endpoint, excluding a2a/mcp protocol bridges.
            //   3. Any other /atmosphere/* path that is not the admin surface.
            // The default-first step keeps samples that ship multiple AI endpoints
            // (e.g. quarkus-ai-chat with ai-chat + review-extractor) connected to
            // the canonical chat surface instead of the first iteration-order match.
            if (handlers.containsKey(DEFAULT_AI_ENDPOINT)) {
                return DEFAULT_AI_ENDPOINT;
            }
            for (var path : handlers.keySet()) {
                if (path.startsWith("/atmosphere/agent/") && !path.contains("/a2a") && !path.contains("/mcp")) {
                    return path;
                }
            }
            for (var path : handlers.keySet()) {
                if (path.startsWith("/atmosphere/") && !path.contains("/admin")) {
                    return path;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not enumerate handlers", e);
        }
        return DEFAULT_AI_ENDPOINT;
    }

    private static String detectMode(AtmosphereFramework framework, String endpoint) {
        if (framework == null || endpoint == null) {
            return "ai";
        }
        try {
            var wrapper = framework.getAtmosphereHandlers().get(endpoint);
            if (wrapper == null) {
                return "ai";
            }
            var handlerClassName = wrapper.atmosphereHandler().getClass().getName();
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

    private static String detectRuntime() {
        // Reach AgentRuntimeResolver via reflection so this servlet keeps no
        // hard compile-time link to atmosphere-ai. Mirrors the Spring
        // controller's catch-LinkageError defence.
        try {
            var resolverCls = Class.forName("org.atmosphere.ai.AgentRuntimeResolver");
            var resolveMethod = resolverCls.getMethod("resolve");
            var runtime = resolveMethod.invoke(null);
            if (runtime == null) {
                return "unknown";
            }
            return (String) runtime.getClass().getMethod("name").invoke(runtime);
        } catch (LinkageError | ReflectiveOperationException e) {
            logger.debug("Could not resolve AgentRuntime", e);
            return "unknown";
        }
    }

    private static String orDefault(String s, String fallback) {
        return s == null ? fallback : s;
    }

    /**
     * Hand-rolled JSON serializer for a flat map of {@code String} keys to
     * {@code String} or {@code Boolean} values. The full key/value set is
     * controlled by this servlet — strings are quoted/escaped, booleans are
     * emitted unquoted — so no nested types flow through and the runtime POM
     * stays Jackson-free.
     */
    private static String toJson(Map<String, Object> entries) {
        var sb = new StringBuilder().append('{');
        var first = true;
        for (var e : entries.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            var value = e.getValue();
            if (value instanceof Boolean b) {
                sb.append(b.toString());
            } else {
                sb.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String raw) {
        if (raw == null) return "";
        var sb = new StringBuilder(raw.length() + 4);
        for (var i = 0; i < raw.length(); i++) {
            var c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
