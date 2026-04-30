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
 * <p>The optional {@code condition} field carries a Phase-5 condition
 * expression evaluated against the call's resolved arguments. Phase 1 and
 * Phase 3 ignore it; Phase 5 wires the condition grammar.</p>
 *
 * @param fromState origin state name.
 * @param toState   destination state name.
 * @param toolName  the tool whose invocation triggers this transition.
 * @param condition optional condition expression; may be {@code null}.
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
