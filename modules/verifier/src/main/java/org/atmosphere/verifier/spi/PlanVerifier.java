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
package org.atmosphere.verifier.spi;

import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.policy.Policy;

/**
 * Static check that runs against a {@link Workflow} +
 * {@link Policy} pair before any tool fires. Implementations are
 * discovered via {@link java.util.ServiceLoader} from
 * {@code META-INF/services/org.atmosphere.verifier.spi.PlanVerifier}.
 *
 * <p>Verifiers are pure functions: same inputs → same result, no side
 * effects, no IO, no tool calls. They are safe to invoke from anywhere
 * — including policy-authoring tooling that wants to validate a plan
 * without instantiating the full execution stack.</p>
 *
 * <p>The {@link #priority()} value orders verifiers within a chain (lower
 * runs first). Cheap structural checks (allowlist, well-formedness)
 * report at priority 10–20 so dependent checks (taint, automata) skip
 * traversing already-malformed plans.</p>
 *
 * @see java.util.ServiceLoader
 */
public interface PlanVerifier {

    /**
     * Stable identifier surfaced in {@link Violation#category()} and in
     * verifier-chain diagnostics. Conventionally lower-kebab-case.
     */
    String name();

    /**
     * Lower priority runs earlier. Default 100; cheap structural checks
     * use 10–20, expensive proof-based checks 200+.
     */
    default int priority() {
        return 100;
    }

    /**
     * Run the check. Implementations must not mutate any of the inputs
     * and must not perform IO.
     *
     * @param workflow the plan AST.
     * @param policy   the declarative security policy.
     * @param registry the tool registry for cross-checking tool existence
     *                 and metadata. May be consulted but not mutated.
     * @return the verification result; never {@code null}. Empty
     *         violations list signals success.
     */
    VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry);
}
