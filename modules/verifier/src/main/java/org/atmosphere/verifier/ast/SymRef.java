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

import java.util.Objects;

/**
 * Symbolic reference to a binding produced by an earlier
 * {@link WorkflowStep}. The reference is resolved against the executor's
 * environment at run time — never at plan-generation time. This is the
 * mechanism that keeps attacker-controlled data out of the LLM's
 * decision-making path.
 *
 * @param ref the binding name; non-null and non-blank.
 */
public record SymRef(String ref) {
    public SymRef {
        Objects.requireNonNull(ref, "ref");
        if (ref.isBlank()) {
            throw new IllegalArgumentException("SymRef.ref must not be blank");
        }
    }
}
