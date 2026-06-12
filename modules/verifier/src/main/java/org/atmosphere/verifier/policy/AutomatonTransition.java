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

import java.util.Objects;

/**
 * Single transition in a {@link SecurityAutomaton}: when a step calls
 * {@code toolName}, advance from {@code fromState} to {@code toState}.
 *
 * <p>The optional {@code condition} field carries a guard expression in the
 * {@link Condition} grammar, evaluated against the triggering call's
 * arguments. When {@code null} the transition is unconditional. When set,
 * the {@link org.atmosphere.verifier.checks.AutomatonVerifier} fires the
 * transition only when the guard can hold — and, when the guard's truth is
 * not statically known (e.g. an argument is a symbolic reference), it
 * soundly explores both the taken and not-taken successors.</p>
 *
 * @param fromState origin state name.
 * @param toState   destination state name.
 * @param toolName  the tool whose invocation triggers this transition.
 * @param condition optional guard expression; may be {@code null}.
 */
public record AutomatonTransition(String fromState,
                                  String toState,
                                  String toolName,
                                  String condition) {
    public AutomatonTransition {
        Objects.requireNonNull(fromState, "fromState");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(toolName, "toolName");
        if (fromState.isBlank() || toState.isBlank() || toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "fromState, toState, and toolName must not be blank");
        }
        // condition may be null — guarded transitions are optional
    }
}
