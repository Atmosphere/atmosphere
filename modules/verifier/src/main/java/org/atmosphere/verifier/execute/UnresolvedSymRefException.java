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

/**
 * Raised when {@link WorkflowExecutor} encounters a
 * {@link org.atmosphere.verifier.ast.SymRef} whose target is missing from
 * the run environment. A correctly-verified plan never throws this — the
 * {@link org.atmosphere.verifier.checks.WellFormednessVerifier} detects the
 * condition statically. This exception exists as a fail-loud belt-and-
 * suspenders against a verifier that mistakenly accepts a forward reference
 * (correctness invariant #4: surface boundary errors as typed exceptions,
 * never as {@link NullPointerException}).
 */
public final class UnresolvedSymRefException extends RuntimeException {

    private final String ref;
    private final int stepIndex;

    public UnresolvedSymRefException(String ref, int stepIndex) {
        super("Unresolved SymRef '" + ref + "' at step index " + stepIndex
                + " — workflow should have failed WellFormednessVerifier");
        this.ref = ref;
        this.stepIndex = stepIndex;
    }

    public String ref() {
        return ref;
    }

    public int stepIndex() {
        return stepIndex;
    }
}
