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
package org.atmosphere.verifier.checks;

import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.SmtChecker;
import org.atmosphere.verifier.spi.VerificationResult;

import java.util.Objects;

/**
 * Adapts a {@link SmtChecker} into the {@link PlanVerifier} chain. The
 * default constructor resolves the highest-priority available checker
 * via {@link SmtChecker#resolve()} — when no real solver is on the
 * classpath this is the
 * {@link org.atmosphere.verifier.spi.SmtChecker.NoOpSmtChecker}, which
 * always reports green.
 *
 * <p>Priority 200 — runs <em>after</em> every cheap structural check.
 * SMT proofs are the most expensive verifier in the suite; we never
 * pay them on a plan that already failed allowlist or taint.</p>
 */
public final class SmtVerifier implements PlanVerifier {

    private final SmtChecker checker;

    public SmtVerifier() {
        this(SmtChecker.resolve());
    }

    public SmtVerifier(SmtChecker checker) {
        this.checker = Objects.requireNonNull(checker, "checker");
    }

    @Override
    public String name() {
        return "smt";
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public VerificationResult verify(Workflow workflow, Policy policy, ToolRegistry registry) {
        return checker.check(workflow, policy);
    }

    /** @return the underlying {@link SmtChecker}; useful for tests / diagnostics. */
    public SmtChecker checker() {
        return checker;
    }
}
