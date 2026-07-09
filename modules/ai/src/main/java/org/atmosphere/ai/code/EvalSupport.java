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
package org.atmosphere.ai.code;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The integration seam for the in-process {@code eval} feature — the sandboxed
 * interpreter that complements the container-backed {@code code_exec}. Mirrors
 * {@link CodeExecSupport}: a process-wide {@link #shared()} instance resolved
 * from system properties (default deny), an {@link #isEnabled()} check that
 * reflects <em>confirmed runtime state</em> (Correctness Invariant #5), and a
 * {@link #tool()} definition registered only when enabled.
 *
 * <p>The concrete interpreter is pluggable: {@link EvalEngine} is a
 * {@link ServiceLoader} SPI, so the highest-{@link EvalEngine#priority() priority}
 * available engine wins — exactly like {@code AgentRuntime} resolution. Rhino
 * (JavaScript) ships as the default; another engine takes over simply by being
 * on the classpath at a higher priority.</p>
 *
 * <p>The evaluator is stateless across calls, so — unlike {@code code_exec} —
 * there is no per-session sandbox to install: the tool executor calls
 * {@link #evaluate(String)} directly.</p>
 */
public final class EvalSupport {

    private static final Logger logger = LoggerFactory.getLogger(EvalSupport.class);

    private final boolean available;
    private final EvalEngine engine;
    private final EvalLimits limits;

    public EvalSupport(EvalConfig config) {
        this.limits = EvalLimits.from(config);
        EvalEngine resolved = config.enabled() ? resolveEngine() : null;
        if (config.enabled() && resolved == null) {
            logger.warn("eval is enabled ({}=true) but no EvalEngine is available on the "
                    + "classpath — the eval tool will not be offered. Add an engine such "
                    + "as 'org.mozilla:rhino' (the default JavaScript engine).",
                    EvalConfig.ENABLED);
        }
        this.engine = resolved;
        this.available = resolved != null;
        if (available) {
            logger.debug("eval engine resolved: {} ({})",
                    engine.getClass().getSimpleName(), engine.language());
        }
    }

    /** Resolve from {@code org.atmosphere.ai.eval.*} configuration (default deny). */
    public static EvalSupport fromSystemProperties() {
        return new EvalSupport(EvalConfig.fromSystemProperties());
    }

    private static volatile EvalSupport shared;

    /**
     * The process-wide instance resolved from system properties, shared by the
     * tool-registration and tool-execution sites so they observe one gating
     * decision and one engine. Lazily initialized.
     */
    public static EvalSupport shared() {
        var instance = shared;
        if (instance == null) {
            synchronized (EvalSupport.class) {
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
     * The highest-priority available {@link EvalEngine} from the
     * {@link ServiceLoader}, or {@code null} when none can run. Each service is
     * probed defensively — a service that fails to instantiate (e.g. its backing
     * dependency is absent) is skipped, never fatal.
     */
    private static EvalEngine resolveEngine() {
        var candidates = new java.util.ArrayList<EvalEngine>();
        try {
            var it = ServiceLoader.load(EvalEngine.class).iterator();
            while (it.hasNext()) {
                try {
                    candidates.add(it.next());
                } catch (ServiceConfigurationError e) {
                    // Impl could not load (missing optional dependency) — skip it.
                    logger.debug("EvalEngine skipped (load failure): {}", e.getMessage());
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.debug("ServiceLoader<EvalEngine> error: {}", e.getMessage());
        }
        return select(candidates);
    }

    /**
     * Pick the highest-{@link EvalEngine#priority() priority} engine whose
     * {@link EvalEngine#isAvailable()} returns {@code true}. Package-private and
     * side-effect free so the selection rule is unit-testable without
     * {@link ServiceLoader} plumbing.
     *
     * @param engines the candidate engines (any discovery order)
     * @return the winning engine, or {@code null} when none is available
     */
    static EvalEngine select(Iterable<EvalEngine> engines) {
        EvalEngine best = null;
        for (var candidate : engines) {
            try {
                if (candidate.isAvailable()
                        && (best == null || candidate.priority() > best.priority())) {
                    best = candidate;
                }
            } catch (RuntimeException e) {
                logger.debug("EvalEngine availability check failed for {}: {}",
                        candidate.getClass().getName(), e.getMessage());
            }
        }
        return best;
    }

    /**
     * Whether the {@code eval} tool should be offered: enabled in config and an
     * engine confirmed available (Correctness Invariant #5).
     */
    public boolean isEnabled() {
        return available;
    }

    /** The language of the resolved engine (e.g. {@code "javascript"}). */
    public String language() {
        return available ? engine.language() : "javascript";
    }

    /** The tool definition to register when {@link #isEnabled()}. */
    public ToolDefinition tool() {
        return EvalTool.definition();
    }

    /**
     * Evaluate one script in a fresh sandboxed scope. Returns an error result
     * (never throws) when the feature is disabled or the script fails, so the
     * model reads the failure as a tool result and can correct course.
     */
    public EvalResult evaluate(String code) {
        if (!available) {
            return EvalResult.error("The eval tool is not enabled on this server.");
        }
        return engine.evaluate(code, limits);
    }
}
