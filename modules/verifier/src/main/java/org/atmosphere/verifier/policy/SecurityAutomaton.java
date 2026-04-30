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
package org.atmosphere.verifier.policy;

import java.util.List;
import java.util.Objects;

/**
 * Finite-state security automaton over a workflow's tool-call sequence.
 *
 * <p>The Phase 5 {@code AutomatonVerifier} symbolically executes a plan
 * against every {@code SecurityAutomaton} in the {@link Policy}: each
 * {@code ToolCallNode} advances the automaton via the matching
 * {@link AutomatonTransition}; if any reachable state has
 * {@link AutomatonState#isError()} set, the verifier emits a violation.
 * Phase 1 stores these records but does not execute them.</p>
 *
 * @param name         identifier surfaced in diagnostics; non-blank.
 * @param states       all states; must include {@code initialState}.
 * @param transitions  edge set; defensively copied.
 * @param initialState name of the start state; must appear in
 *                     {@code states}.
 */
public record SecurityAutomaton(String name,
                                List<AutomatonState> states,
                                List<AutomatonTransition> transitions,
                                String initialState) {
    public SecurityAutomaton {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(transitions, "transitions");
        Objects.requireNonNull(initialState, "initialState");
        if (name.isBlank() || initialState.isBlank()) {
            throw new IllegalArgumentException(
                    "name and initialState must not be blank");
        }
        states = List.copyOf(states);
        transitions = List.copyOf(transitions);
        boolean initialPresent = states.stream()
                .anyMatch(s -> s.name().equals(initialState));
        if (!initialPresent) {
            throw new IllegalArgumentException(
                    "initialState '" + initialState
                            + "' is not declared in states");
        }
    }
}
