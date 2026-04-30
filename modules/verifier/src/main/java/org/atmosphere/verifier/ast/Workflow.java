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
package org.atmosphere.verifier.ast;

import java.util.List;
import java.util.Objects;

/**
 * Verified-plan AST root. A {@code Workflow} is a list of
 * {@link WorkflowStep}s executed in order; downstream steps may reference
 * the bound results of earlier steps via {@link SymRef}.
 *
 * <p>The {@code goal} field carries the user-facing intent that produced
 * this plan (a one-line summary of the user's prompt). Verifiers ignore
 * it; it surfaces in execution traces and audit logs to make plans
 * grep-friendly.</p>
 *
 * @param goal  short description of what this workflow is meant to
 *              accomplish; non-blank.
 * @param steps the ordered list of steps; defensively copied.
 */
public record Workflow(String goal, List<WorkflowStep> steps) {
    public Workflow {
        Objects.requireNonNull(goal, "goal");
        if (goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
    }
}
