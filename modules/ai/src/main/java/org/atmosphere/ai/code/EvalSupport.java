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

import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The integration seam for the in-process {@code eval} feature — the sandboxed
 * JavaScript evaluator that complements the container-backed {@code code_exec}.
 * Mirrors {@link CodeExecSupport}: a process-wide {@link #shared()} instance
 * resolved from system properties (default deny), an {@link #isEnabled()} check
 * that reflects <em>confirmed runtime state</em> (Correctness Invariant #5), and
 * a {@link #tool()} definition registered only when enabled.
 *
 * <p>The evaluator is stateless across calls, so — unlike {@code code_exec} —
 * there is no per-session sandbox to install: the tool executor calls
 * {@link #evaluate(String)} directly.</p>
 *
 * <p><strong>Runtime truth.</strong> {@link #isEnabled()} is {@code true} only
 * when the feature is switched on in config <em>and</em> the Rhino engine is on
 * the classpath (it is an optional dependency). Enabled-but-absent is logged
 * loudly and reports {@code false} rather than advertising a tool that cannot
 * run.</p>
 */
public final class EvalSupport {

    private static final Logger logger = LoggerFactory.getLogger(EvalSupport.class);

    private final boolean available;
    private final RhinoEvalEngine engine;

    public EvalSupport(EvalConfig config) {
        boolean rhinoPresent = isRhinoPresent();
        if (config.enabled() && !rhinoPresent) {
            logger.warn("eval is enabled ({}=true) but the Rhino engine is not on the "
                    + "classpath — the eval tool will not be offered. Add the "
                    + "'org.mozilla:rhino' dependency to enable it.", EvalConfig.ENABLED);
        }
        this.available = config.enabled() && rhinoPresent;
        // Construct the Rhino-backed engine only when available, so the
        // Rhino-referencing class is never loaded when the dependency is absent.
        this.engine = available
                ? new RhinoEvalEngine(config.instructionBudget(),
                        config.timeoutMillis(), config.maxOutputChars())
                : null;
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
     * Whether the {@code eval} tool should be offered: enabled in config and the
     * engine confirmed present on the classpath (Correctness Invariant #5).
     */
    public boolean isEnabled() {
        return available;
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
        if (!available || engine == null) {
            return EvalResult.error("The eval tool is not enabled on this server.");
        }
        return engine.evaluate(code);
    }

    private static boolean isRhinoPresent() {
        try {
            Class.forName("org.mozilla.javascript.Context");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
