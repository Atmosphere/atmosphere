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

import java.util.Objects;

/**
 * Single policy violation surfaced by a {@link PlanVerifier}.
 *
 * <p>The {@code category} string maps a violation to its check class
 * (e.g. {@code "allowlist"}, {@code "well-formed"}, {@code "taint"}). UI
 * and audit tooling group by category; the executor refuses any plan
 * whose verification result carries even one violation in any category.</p>
 *
 * <p>{@code astPath} is a structural pointer into the
 * {@link org.atmosphere.verifier.ast.Workflow} (e.g.
 * {@code "steps[2].arguments.body"}). It is human-readable and stable
 * across re-runs — verifier diagnostics, audit logs, and admin UI all
 * key off the same path syntax.</p>
 *
 * @param category short identifier of the check class; non-blank.
 * @param message  human-readable explanation; non-blank.
 * @param astPath  structural pointer to the offending node; may be
 *                 {@code null} when the violation is workflow-wide rather
 *                 than tied to a specific node.
 */
public record Violation(String category, String message, String astPath) {
    public Violation {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(message, "message");
        if (category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        // astPath may be null — workflow-wide violations have no path
    }

    /**
     * Convenience: build a violation without an AST path.
     */
    public static Violation of(String category, String message) {
        return new Violation(category, message, null);
    }
}
