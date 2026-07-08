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

import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

/**
 * In-process JavaScript evaluator backed by Mozilla Rhino, sandboxed for
 * untrusted, model-generated code. This is the engine behind the {@code eval}
 * tool — a lightweight, container-free counterpart to {@code code_exec}.
 *
 * <p><strong>Isolation (Correctness Invariants #4, #6).</strong></p>
 * <ul>
 *   <li><b>No host reach.</b> The scope is built with Rhino's
 *       {@code initSafeStandardObjects()}, which installs the
 *       ECMAScript built-ins (<code>Math</code>, <code>JSON</code>,
 *       <code>Array</code>, …) but <em>none</em> of Rhino's LiveConnect Java
 *       bridge (<code>java</code>, <code>Packages</code>, <code>getClass</code>).
 *       A deny-all {@link ClassShutter} is layered on top so no Java class is
 *       resolvable even via a reflective escape. Rhino JS has no built-in file
 *       or network I/O, so the scope cannot touch the host.</li>
 *   <li><b>Bounded CPU.</b> Optimization is disabled (interpreted mode) so the
 *       instruction observer fires; a runaway loop trips the instruction budget
 *       or the wall-clock deadline and aborts with a Java {@link Error} — which
 *       script <code>try/catch</code> cannot swallow.</li>
 *   <li><b>Bounded output.</b> The serialized result is capped by the caller.</li>
 * </ul>
 *
 * <p>Each {@link #evaluate(String)} runs in a <em>fresh</em> scope — there is no
 * cross-call state — so one evaluation cannot see or corrupt another's.</p>
 */
final class RhinoEvalEngine {

    private final int maxOutputChars;
    private final SandboxContextFactory factory;

    RhinoEvalEngine(int instructionBudget, long timeoutMillis, int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
        int threshold = Math.max(1_000, Math.min(instructionBudget, 100_000));
        this.factory = new SandboxContextFactory(
                Math.max(1, instructionBudget / threshold), threshold, timeoutMillis);
    }

    /**
     * Evaluate one script in a fresh sandboxed scope. Errors — syntax, runtime,
     * or a tripped CPU/time budget — come back as {@link EvalResult#error},
     * never as a thrown exception, so the model can read and correct them.
     */
    EvalResult evaluate(String script) {
        if (script == null || script.isBlank()) {
            return EvalResult.error("'code' is required");
        }
        // Arm the per-thread budget around the synchronous call so the
        // instruction observer (same thread) sees it.
        factory.armBudget();
        try {
            return factory.call(cx -> {
                cx.setClassShutter(DENY_ALL);
                Scriptable scope = cx.initSafeStandardObjects();
                try {
                    Object result = cx.evaluateString(scope, script, "eval", 1, null);
                    String text = serialize(cx, scope, result);
                    boolean truncated = text.length() > maxOutputChars;
                    return EvalResult.ok(
                            truncated ? text.substring(0, maxOutputChars) : text, truncated);
                } catch (RhinoException e) {
                    // Syntax / runtime JS errors — surface the message, not a Java trace.
                    return EvalResult.error(e.getMessage());
                }
            });
        } catch (EvalBudgetExceeded e) {
            // The budget abort unwinds past factory.call as a Java Error.
            return EvalResult.error(e.getMessage());
        } catch (RuntimeException e) {
            return EvalResult.error("Evaluation failed: " + e.getMessage());
        } finally {
            factory.disarmBudget();
        }
    }

    /** Serialize the result: JSON for objects/arrays, JS string coercion otherwise. */
    private static String serialize(Context cx, Scriptable scope, Object result) {
        if (result == null || result instanceof Undefined) {
            return "undefined";
        }
        if (result instanceof Scriptable) {
            Object json = NativeJSON.stringify(cx, scope, result, null, "");
            if (json instanceof CharSequence js) {
                return js.toString();
            }
        }
        return Context.toString(result);
    }

    /** Deny-all class visibility — no Java type is resolvable from a script. */
    private static final ClassShutter DENY_ALL = fullClassName -> false;

    /**
     * A {@link ContextFactory} that runs every context in interpreted mode with
     * an instruction observer, so a per-thread budget can abort runaway scripts.
     */
    private static final class SandboxContextFactory extends ContextFactory {

        private final int maxObserverFires;
        private final int observerThreshold;
        private final long timeoutMillis;
        private final ThreadLocal<Budget> budget = new ThreadLocal<>();

        SandboxContextFactory(int maxObserverFires, int observerThreshold, long timeoutMillis) {
            this.maxObserverFires = maxObserverFires;
            this.observerThreshold = observerThreshold;
            this.timeoutMillis = timeoutMillis;
        }

        void armBudget() {
            budget.set(new Budget(
                    System.nanoTime() + timeoutMillis * 1_000_000L, maxObserverFires));
        }

        void disarmBudget() {
            budget.remove();
        }

        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            // Interpreted mode is REQUIRED: the instruction observer never fires
            // under the optimizing compiler, so the CPU guard would be a no-op.
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(observerThreshold);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Budget b = budget.get();
            if (b == null) {
                return;
            }
            if (System.nanoTime() > b.deadlineNanos) {
                throw new EvalBudgetExceeded(
                        "Evaluation exceeded the " + timeoutMillis + "ms time limit "
                        + "(possible infinite loop).");
            }
            if (--b.firesLeft <= 0) {
                throw new EvalBudgetExceeded(
                        "Evaluation exceeded the instruction budget "
                        + "(possible infinite loop).");
            }
        }

        private static final class Budget {
            final long deadlineNanos;
            int firesLeft;

            Budget(long deadlineNanos, int firesLeft) {
                this.deadlineNanos = deadlineNanos;
                this.firesLeft = firesLeft;
            }
        }
    }

    /**
     * Fatal abort raised from the instruction observer when a script overruns
     * its CPU or time budget. Extends {@link Error} so a script
     * {@code try/catch} — which only traps ECMAScript exceptions — cannot
     * swallow it and keep running.
     */
    private static final class EvalBudgetExceeded extends Error {
        EvalBudgetExceeded(String message) {
            super(message);
        }
    }
}
