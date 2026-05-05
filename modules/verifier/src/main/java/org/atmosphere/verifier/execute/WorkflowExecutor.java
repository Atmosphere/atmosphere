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
package org.atmosphere.verifier.execute;

import org.atmosphere.verifier.ast.PlanNode;
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a verified {@link Workflow} step-by-step. Resolves
 * {@link SymRef} arguments against the run environment of bound results
 * and dispatches each step's tool call through a pluggable
 * {@link ToolDispatcher}.
 *
 * <p><strong>Scope</strong> — synchronous, single-threaded execution
 * with deep SymRef resolution (top-level and nested values inside
 * {@code Map} / {@code List}). Async dispatch is additive via a sibling
 * {@code runAsync()} method that submits to a caller-supplied executor
 * without changing this class's signature (correctness invariant #7 —
 * mode parity).</p>
 *
 * <p><strong>Ownership</strong> (correctness invariant #1) — the
 * executor never closes the {@link ToolDispatcher} it was given;
 * lifecycle is the caller's.</p>
 *
 * <p><strong>Terminal-path completeness</strong> (correctness invariant
 * #2) — every exit path leaves a defined env state:</p>
 * <ul>
 *   <li>Success → returns the full env.</li>
 *   <li>Tool failure → throws {@link WorkflowExecutionException} whose
 *       {@code partialEnv()} contains every binding produced before the
 *       failing step.</li>
 *   <li>Unresolved SymRef → throws {@link UnresolvedSymRefException}
 *       (typed; never NPE).</li>
 * </ul>
 */
public final class WorkflowExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final ToolDispatcher dispatcher;

    public WorkflowExecutor(ToolDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /**
     * Execute {@code workflow}. Returns an immutable snapshot of the
     * environment containing every binding produced by every successful
     * step.
     *
     * @param workflow   the verified plan to run.
     * @param initialEnv pre-populated bindings (e.g. user input). May be
     *                   empty; never null. Defensive copy is taken.
     * @return immutable map of all bindings produced (initial + per-step).
     * @throws WorkflowExecutionException on tool failure; carries the
     *         partial env up to the failing step.
     * @throws UnresolvedSymRefException  when a SymRef cannot be resolved
     *         (signals a verifier bug — well-formedness should have
     *         caught it).
     */
    public Map<String, Object> run(Workflow workflow, Map<String, Object> initialEnv) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(initialEnv, "initialEnv");

        Map<String, Object> env = new LinkedHashMap<>(initialEnv);
        var steps = workflow.steps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            executeStep(step, i, env);
        }
        return Map.copyOf(env);
    }

    private void executeStep(WorkflowStep step, int index, Map<String, Object> env) {
        PlanNode node = step.node();
        if (node instanceof ToolCallNode call) {
            executeToolCall(call, step.label(), index, env);
            return;
        }
        // Sealed hierarchy — Phase 5 adds Conditional / Loop. The
        // exhaustive pattern match is the contract that forces every
        // verifier and the executor to be updated together.
        throw new IllegalStateException(
                "Unhandled PlanNode kind at step " + index + ": "
                        + node.getClass().getName());
    }

    private void executeToolCall(ToolCallNode call,
                                 String stepLabel,
                                 int stepIndex,
                                 Map<String, Object> env) {
        Map<String, Object> resolved = resolveArguments(call, stepIndex, env);
        String result;
        try {
            result = dispatcher.dispatch(call.toolName(), resolved);
        } catch (RuntimeException ex) {
            // Snapshot env BEFORE this step's binding lands so the
            // caller can audit which bindings successfully landed.
            throw new WorkflowExecutionException(
                    "Workflow aborted at step " + stepIndex
                            + " ('" + stepLabel + "') — tool '"
                            + call.toolName() + "' failed: " + ex.getMessage(),
                    env,
                    call.resultBinding(),
                    stepLabel,
                    stepIndex,
                    ex);
        }
        if (call.hasResultBinding()) {
            env.put(call.resultBinding(), result);
        } else if (logger.isTraceEnabled()) {
            logger.trace("Step {} ('{}') discarded result from '{}' (no binding)",
                    stepIndex, stepLabel, call.toolName());
        }
    }

    /**
     * Resolve {@link SymRef} values in the argument map against
     * {@code env}, descending into nested {@link Map} and {@link List}
     * values. Mirrors the Python reference implementation's
     * {@code _normalize_refs} traversal: every SymRef encountered at any
     * depth is replaced with its bound value, while non-SymRef leaves
     * pass through unchanged.
     */
    private Map<String, Object> resolveArguments(ToolCallNode call,
                                                 int stepIndex,
                                                 Map<String, Object> env) {
        Map<String, Object> resolved = new LinkedHashMap<>(call.arguments().size());
        for (var entry : call.arguments().entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), stepIndex, env));
        }
        return resolved;
    }

    private Object resolveValue(Object value, int stepIndex, Map<String, Object> env) {
        if (value instanceof SymRef ref) {
            if (!env.containsKey(ref.ref())) {
                throw new UnresolvedSymRefException(ref.ref(), stepIndex);
            }
            return env.get(ref.ref());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            for (var e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), resolveValue(e.getValue(), stepIndex, env));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(resolveValue(element, stepIndex, env));
            }
            return out;
        }
        return value;
    }
}
