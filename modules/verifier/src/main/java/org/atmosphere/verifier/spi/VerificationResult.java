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

import java.util.List;
import java.util.Objects;

/**
 * Aggregate result of running one or more {@link PlanVerifier}s against a
 * {@link org.atmosphere.verifier.ast.Workflow} +
 * {@link org.atmosphere.verifier.policy.Policy} pair.
 *
 * <p>The {@code ok} flag is implied by an empty {@code violations} list;
 * the executor short-circuits on any non-empty result. The {@code ok}
 * accessor is provided as a syntactic convenience and is always
 * derived — never set independently of {@code violations}.</p>
 *
 * @param violations list of policy violations; defensively copied; may be
 *                   empty (in which case the result is {@link #ok()}).
 */
public record VerificationResult(List<Violation> violations) {
    public VerificationResult {
        Objects.requireNonNull(violations, "violations");
        violations = List.copyOf(violations);
    }

    /**
     * @return {@code true} when no violations were emitted.
     */
    public boolean isOk() {
        return violations.isEmpty();
    }

    /**
     * Empty (passing) result.
     */
    public static VerificationResult ok() {
        return new VerificationResult(List.of());
    }

    /**
     * Result carrying the supplied violations.
     */
    public static VerificationResult of(List<Violation> violations) {
        return new VerificationResult(violations);
    }

    /**
     * Convenience for building a result with a single violation.
     */
    public static VerificationResult of(Violation violation) {
        return new VerificationResult(List.of(violation));
    }

    /**
     * Combine this result with another, preserving violation order.
     * Verifier-chain runners use this to aggregate the per-verifier
     * results into a single {@code VerificationResult} for the executor.
     */
    public VerificationResult merge(VerificationResult other) {
        Objects.requireNonNull(other, "other");
        if (other.violations.isEmpty()) {
            return this;
        }
        if (this.violations.isEmpty()) {
            return other;
        }
        var combined = new java.util.ArrayList<Violation>(
                this.violations.size() + other.violations.size());
        combined.addAll(this.violations);
        combined.addAll(other.violations);
        return new VerificationResult(combined);
    }
}
