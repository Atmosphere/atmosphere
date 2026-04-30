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
import java.util.Set;

/**
 * Declarative security policy consumed by the static
 * {@link org.atmosphere.verifier.spi.PlanVerifier} chain.
 *
 * <p>The same record is intended to feed Atmosphere's runtime
 * {@code GovernancePolicy} chain in a follow-up phase, so that a single
 * declaration enforces at both layers — defense in depth from one
 * source.</p>
 *
 * <p>In Phase 1 only {@code allowedTools} is consulted (by
 * {@link org.atmosphere.verifier.checks.AllowlistVerifier}). The
 * {@code taintRules} list is consumed by Phase 3's {@code TaintVerifier};
 * {@code automata} are consumed by Phase 5's {@code AutomatonVerifier}.
 * The records are present from day one so authors can declare the full
 * security posture and pick up new checks as they ship without rewriting
 * their YAML.</p>
 *
 * @param name         identifier surfaced in diagnostics; non-blank.
 * @param allowedTools tools that may appear in a verified plan; defensively
 *                     copied; non-null.
 * @param taintRules   dataflow restrictions; defensively copied; may be
 *                     empty.
 * @param automata     security automata; defensively copied; may be empty.
 */
public record Policy(String name,
                     Set<String> allowedTools,
                     List<TaintRule> taintRules,
                     List<SecurityAutomaton> automata) {

    public Policy {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(allowedTools, "allowedTools");
        Objects.requireNonNull(taintRules, "taintRules");
        Objects.requireNonNull(automata, "automata");
        allowedTools = Set.copyOf(allowedTools);
        taintRules = List.copyOf(taintRules);
        automata = List.copyOf(automata);
    }

    /**
     * Convenience: build a Policy that only declares an allowlist (the
     * common Phase 1 case).
     */
    public static Policy allowlist(String name, String... tools) {
        return new Policy(name, Set.of(tools), List.of(), List.of());
    }
}
