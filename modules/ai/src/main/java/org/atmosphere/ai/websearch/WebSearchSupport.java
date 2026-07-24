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
package org.atmosphere.ai.websearch;

import java.util.ArrayList;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The integration seam for the {@code web_search} feature. Mirrors the sibling
 * {@link org.atmosphere.ai.code.EvalSupport}: a process-wide {@link #shared()}
 * instance, an {@link #isEnabled()} check that reflects <em>confirmed runtime
 * state</em> (Correctness Invariant #5), a {@link #tool()} definition registered
 * only when enabled, and a {@link #search(WebSearchQuery)} entry point the tool
 * executor and first-party agents call directly.
 *
 * <p>The concrete backend is pluggable: {@link WebSearchEngine} is a
 * {@link ServiceLoader} SPI, so the highest-{@link WebSearchEngine#priority()
 * priority} <em>configured</em> engine wins — exactly like {@code AgentRuntime}
 * resolution. {@link HttpWebSearchEngine} (JSON over HTTP) ships as the default;
 * another engine takes over simply by being on the classpath, configured, at a
 * higher priority.</p>
 *
 * <p><strong>Fail closed.</strong> When no engine is configured, {@link
 * #isEnabled()} is {@code false} (so the tool is never advertised to a model)
 * and {@link #search(WebSearchQuery)} returns a clear
 * {@link WebSearchResults#unavailable} notice without touching the network — the
 * offline default (Correctness Invariants #5, #6).</p>
 */
public final class WebSearchSupport {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchSupport.class);

    private final WebSearchEngine engine;

    /**
     * @param engine the resolved engine; {@code null} falls back to a disabled
     *               {@link HttpWebSearchEngine} so the seam always has a
     *               non-null, fail-closed engine to answer through
     */
    public WebSearchSupport(WebSearchEngine engine) {
        this.engine = engine == null
                ? new HttpWebSearchEngine(WebSearchConfig.disabled()) : engine;
    }

    /** Resolve from {@code org.atmosphere.ai.websearch.*} configuration (fail closed). */
    public static WebSearchSupport fromSystemProperties() {
        return new WebSearchSupport(resolveEngine());
    }

    private static volatile WebSearchSupport shared;

    /**
     * The process-wide instance resolved from system properties, shared by the
     * tool-registration and tool-execution sites so they observe one gating
     * decision and one engine. Lazily initialized.
     *
     * @return the shared instance
     */
    public static WebSearchSupport shared() {
        var instance = shared;
        if (instance == null) {
            synchronized (WebSearchSupport.class) {
                instance = shared;
                if (instance == null) {
                    instance = fromSystemProperties();
                    shared = instance;
                }
            }
        }
        return instance;
    }

    /**
     * The highest-priority <em>configured</em> {@link WebSearchEngine} from the
     * {@link ServiceLoader}, or the default {@link HttpWebSearchEngine} (which
     * reports itself unconfigured) when none is configured — so the seam always
     * has a fail-closed engine to answer through. Each service is probed
     * defensively: an impl that fails to instantiate is skipped, never fatal.
     *
     * @return a non-null engine (configured when one exists, else fail-closed)
     */
    static WebSearchEngine resolveEngine() {
        var candidates = new ArrayList<WebSearchEngine>();
        try {
            var it = ServiceLoader.load(WebSearchEngine.class).iterator();
            while (it.hasNext()) {
                try {
                    candidates.add(it.next());
                } catch (ServiceConfigurationError e) {
                    logger.debug("WebSearchEngine skipped (load failure): {}", e.getMessage());
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.debug("ServiceLoader<WebSearchEngine> error: {}", e.getMessage());
        }
        var selected = select(candidates);
        if (selected != null) {
            logger.debug("web_search engine resolved: {}", selected.name());
            return selected;
        }
        return new HttpWebSearchEngine(WebSearchConfig.fromSystemProperties());
    }

    /**
     * Pick the highest-{@link WebSearchEngine#priority() priority} engine whose
     * {@link WebSearchEngine#isConfigured()} returns {@code true}. Package-private
     * and side-effect free so the selection rule is unit-testable without
     * {@link ServiceLoader} plumbing.
     *
     * @param engines the candidate engines (any discovery order)
     * @return the winning configured engine, or {@code null} when none is configured
     */
    static WebSearchEngine select(Iterable<WebSearchEngine> engines) {
        WebSearchEngine best = null;
        for (var candidate : engines) {
            try {
                if (candidate.isConfigured()
                        && (best == null || candidate.priority() > best.priority())) {
                    best = candidate;
                }
            } catch (RuntimeException e) {
                logger.debug("WebSearchEngine configuration check failed for {}: {}",
                        candidate.getClass().getName(), e.getMessage());
            }
        }
        return best;
    }

    /**
     * Whether the {@code web_search} tool should be offered: a
     * {@link WebSearchEngine} is resolved <em>and configured</em> (Correctness
     * Invariant #5). {@code false} keeps the tool unadvertised and search
     * fail-closed.
     *
     * @return {@code true} when a real search would be attempted
     */
    public boolean isEnabled() {
        return engine.isConfigured();
    }

    /** The name of the resolved engine, for startup logging. */
    public String engineName() {
        return engine.name();
    }

    /** The tool definition to register when {@link #isEnabled()}. */
    public ToolDefinition tool() {
        return WebSearchTool.definition();
    }

    /**
     * Run one search. Returns a {@link WebSearchResults#unavailable fail-closed}
     * outcome (never throws) when the query is empty, no engine is configured, or
     * the engine fails — so callers read the failure as data and can degrade
     * sanely.
     *
     * @param query the query to run
     * @return the outcome, never {@code null}
     */
    public WebSearchResults search(WebSearchQuery query) {
        if (query == null || query.query().isBlank()) {
            return WebSearchResults.unavailable(query == null ? "" : query.query(),
                    "Error: search query is empty.");
        }
        if (!engine.isConfigured()) {
            // Fail closed / offline: the engine itself makes no network call, but
            // short-circuit here too so the notice is uniform regardless of engine.
            return engine.search(query);
        }
        try {
            var result = engine.search(query);
            return result == null
                    ? WebSearchResults.unavailable(query.query(), "Web search returned no response.")
                    : result;
        } catch (RuntimeException e) {
            logger.warn("web_search engine {} threw: {}", engine.name(), e.toString());
            return WebSearchResults.unavailable(query.query(),
                    "Web search failed: " + e.getMessage());
        }
    }
}
