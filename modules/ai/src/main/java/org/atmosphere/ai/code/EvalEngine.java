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

/**
 * SPI for an in-process interpreter behind the {@code eval} tool — a sandboxed,
 * container-free evaluator of model-authored code. Discovered via
 * {@link java.util.ServiceLoader}; the highest-{@link #priority()} engine whose
 * {@link #isAvailable()} returns {@code true} wins, exactly like
 * {@code AgentRuntime} and {@code SandboxProvider} resolution.
 *
 * <p>Atmosphere ships {@link RhinoEvalEngine} (JavaScript, via Mozilla Rhino) as
 * the default. Alternatives — GraalJS, a Python interpreter, a WASM runtime —
 * plug in by adding their jar with a {@code META-INF/services/}
 * {@code org.atmosphere.ai.code.EvalEngine} entry; no Atmosphere change needed.
 *
 * <p><strong>Contract (Correctness Invariants #4, #6).</strong> An engine MUST
 * isolate untrusted code: no host, filesystem, or network reach; it MUST honour
 * the {@link EvalLimits} (CPU/instruction and wall-clock ceilings) so a runaway
 * script aborts rather than hanging; and it MUST return failures as
 * {@link EvalResult#error} data, never by throwing.</p>
 */
public interface EvalEngine {

    /**
     * The source language this engine evaluates (e.g. {@code "javascript"}),
     * surfaced in the tool description and startup logs.
     */
    String language();

    /**
     * Whether this engine can run in the current process — its backing
     * dependency is present and usable. Reports <em>confirmed runtime state</em>
     * (Correctness Invariant #5), never mere configuration intent. Called during
     * {@link java.util.ServiceLoader} resolution; must not throw.
     */
    boolean isAvailable();

    /**
     * Selection weight when several engines are available. Higher wins; the
     * built-in Rhino engine uses {@code 0}, so any explicitly added engine can
     * take precedence with a positive value.
     *
     * @return the priority (default {@code 0})
     */
    default int priority() {
        return 0;
    }

    /**
     * Evaluate one script in a fresh, isolated scope and return its serialized
     * result — or an {@link EvalResult#error} for a syntax/runtime failure or a
     * tripped {@link EvalLimits limit}. Must not throw for script-level failures.
     *
     * @param code   the source to evaluate
     * @param limits the CPU/time/output ceilings to enforce
     * @return the outcome, never {@code null}
     */
    EvalResult evaluate(String code, EvalLimits limits);
}
