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

import java.util.Map;
import java.util.Objects;

/**
 * Raised when a {@link WorkflowExecutor} run aborts at a tool call.
 *
 * <p>Carries an immutable snapshot of the environment built up to (but
 * not including) the failing step so callers can audit what happened —
 * which bindings were produced, where the chain broke, and what the
 * underlying cause was. This satisfies correctness invariant #2
 * (Terminal Path Completeness): every exit path of {@code run()} is a
 * defined post-condition over {@code env}.</p>
 */
public final class WorkflowExecutionException extends RuntimeException {

    private final Map<String, Object> partialEnv;
    private final String failedAtBinding;
    private final String failedAtLabel;
    private final int failedAtIndex;

    public WorkflowExecutionException(String message,
                                      Map<String, Object> partialEnv,
                                      String failedAtBinding,
                                      String failedAtLabel,
                                      int failedAtIndex,
                                      Throwable cause) {
        super(message, cause);
        Objects.requireNonNull(partialEnv, "partialEnv");
        this.partialEnv = Map.copyOf(partialEnv);
        this.failedAtBinding = failedAtBinding;   // may be null for fire-and-forget steps
        this.failedAtLabel = failedAtLabel;
        this.failedAtIndex = failedAtIndex;
    }

    /**
     * Immutable snapshot of every binding produced by successfully-
     * executed steps prior to the failure.
     */
    public Map<String, Object> partialEnv() {
        return partialEnv;
    }

    /**
     * Result-binding name the failing step would have produced (or
     * {@code null} if the step did not declare one).
     */
    public String failedAtBinding() {
        return failedAtBinding;
    }

    /**
     * Human-readable label of the failing step (from
     * {@link org.atmosphere.verifier.ast.WorkflowStep#label()}).
     */
    public String failedAtLabel() {
        return failedAtLabel;
    }

    /**
     * Zero-based index of the failing step in the workflow.
     */
    public int failedAtIndex() {
        return failedAtIndex;
    }
}
