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
 * Single state in a {@link SecurityAutomaton}. {@code error} states cause
 * the automaton verifier (Phase 5) to emit a violation when reached during
 * symbolic execution of the plan's tool sequence.
 *
 * @param name    state identifier; non-blank.
 * @param isError true if entering this state represents a policy violation.
 */
public record AutomatonState(String name, boolean isError) {
    public AutomatonState {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
