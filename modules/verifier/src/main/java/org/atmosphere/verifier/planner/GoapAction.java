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
package org.atmosphere.verifier.planner;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One action in a Goal-Oriented Action Planning (GOAP) domain: a tool call
 * gated by {@code preconditions} (world-state predicates that must already
 * hold) and producing {@code effects} (predicates that hold afterwards). The
 * {@link GoapPlanner} searches over these to assemble an ordered plan that
 * reaches a goal state, then emits it as a verifier
 * {@link org.atmosphere.verifier.ast.Workflow}.
 *
 * <p>World state is modelled as a set of string predicates (a predicate is
 * either present/true or absent/false). An action is applicable in a state
 * when the state contains all of its {@link #preconditions}; applying it adds
 * its {@link #effects} to the state. This keeps the planner deterministic and
 * fully analysable — the same inputs always produce the same plan — which is
 * the property the downstream static verifier relies on.</p>
 *
 * @param label         step label carried into the emitted workflow
 * @param toolName      tool to invoke; must match a registered tool
 * @param preconditions predicates that must hold for this action to apply
 * @param effects       predicates that hold after this action runs; must be
 *                      non-empty so every action makes progress (an action
 *                      with no effect can never advance a plan)
 * @param arguments     argument template for the tool call; literal values or
 *                      {@code "@binding"} symbolic references
 * @param resultBinding optional name to bind the tool result under for
 *                      downstream steps; may be {@code null} or blank
 */
public record GoapAction(String label, String toolName, Set<String> preconditions,
                         Set<String> effects, Map<String, Object> arguments, String resultBinding) {

    public GoapAction {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(toolName, "toolName");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        preconditions = preconditions != null ? Set.copyOf(preconditions) : Set.of();
        Objects.requireNonNull(effects, "effects");
        if (effects.isEmpty()) {
            throw new IllegalArgumentException(
                    "action '" + label + "' must declare at least one effect (else it cannot advance a plan)");
        }
        effects = Set.copyOf(effects);
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
    }

    /** Whether this action is applicable in {@code state}. */
    public boolean applicableIn(Set<String> state) {
        return state.containsAll(preconditions);
    }
}
