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
 * Data-dependent branch: evaluate {@code predicate} against the run
 * environment and execute {@code thenSteps} when it holds, otherwise
 * {@code elseSteps}.
 *
 * <p>A conditional only ever appears in a plan when the governing
 * {@link org.atmosphere.verifier.policy.Policy} opts into
 * {@link org.atmosphere.verifier.policy.ControlFlowMode#BRANCHING}; under
 * the default {@code LINEAR_ONLY} mode the
 * {@link org.atmosphere.verifier.checks.StructureVerifier} refuses any plan
 * that contains one.</p>
 *
 * <p><strong>Soundness contract.</strong> Every verifier descends into
 * <em>both</em> arms — the predicate selects an arm only at run time, so a
 * plan is safe only when both the {@code then} and {@code else} paths are
 * safe. Verifiers never prune an arm by guessing the predicate's value.</p>
 *
 * <p>The {@code predicate} is a guard expression in the
 * {@link org.atmosphere.verifier.policy.Condition} grammar (e.g.
 * {@code "score >= 80"} or {@code "status == approved"}). Names it reads
 * must be bound by an earlier step — the
 * {@link org.atmosphere.verifier.checks.WellFormednessVerifier} enforces
 * that, just as it does for {@link SymRef} arguments.</p>
 *
 * @param predicate the branch-selecting guard expression; non-blank.
 * @param thenSteps steps run when the predicate holds; defensively copied.
 * @param elseSteps steps run when it does not; may be empty; copied.
 */
public record ConditionalNode(String predicate,
                              List<WorkflowStep> thenSteps,
                              List<WorkflowStep> elseSteps) implements PlanNode {

    public ConditionalNode {
        Objects.requireNonNull(predicate, "predicate");
        if (predicate.isBlank()) {
            throw new IllegalArgumentException("predicate must not be blank");
        }
        Objects.requireNonNull(thenSteps, "thenSteps");
        Objects.requireNonNull(elseSteps, "elseSteps");
        thenSteps = List.copyOf(thenSteps);
        elseSteps = List.copyOf(elseSteps);
    }
}
