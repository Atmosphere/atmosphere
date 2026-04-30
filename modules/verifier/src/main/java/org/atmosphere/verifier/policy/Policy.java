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
import java.util.Map;
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
 * <p>Field consumers:</p>
 * <ul>
 *   <li>{@code allowedTools} — Phase 1 {@code AllowlistVerifier}.</li>
 *   <li>{@code taintRules} — Phase 3 {@code TaintVerifier}.</li>
 *   <li>{@code automata} — Phase 5 {@code AutomatonVerifier}.</li>
 *   <li>{@code grantedCapabilities} +
 *       {@code toolCapabilityRequirements} — Phase 5
 *       {@code CapabilityVerifier}.</li>
 * </ul>
 *
 * @param name                       identifier surfaced in diagnostics; non-blank.
 * @param allowedTools               tools that may appear in a verified plan;
 *                                   defensively copied; non-null.
 * @param taintRules                 dataflow restrictions; defensively copied;
 *                                   may be empty.
 * @param automata                   security automata; defensively copied;
 *                                   may be empty.
 * @param grantedCapabilities        capability tokens the agent has been
 *                                   granted; defensively copied; may be empty.
 * @param toolCapabilityRequirements per-tool required-capabilities; the
 *                                   capability verifier rejects any plan that
 *                                   names a tool whose required set has any
 *                                   member not in {@code grantedCapabilities};
 *                                   defensively copied; may be empty.
 */
public record Policy(String name,
                     Set<String> allowedTools,
                     List<TaintRule> taintRules,
                     List<SecurityAutomaton> automata,
                     Set<String> grantedCapabilities,
                     Map<String, Set<String>> toolCapabilityRequirements) {

    public Policy {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(allowedTools, "allowedTools");
        Objects.requireNonNull(taintRules, "taintRules");
        Objects.requireNonNull(automata, "automata");
        Objects.requireNonNull(grantedCapabilities, "grantedCapabilities");
        Objects.requireNonNull(toolCapabilityRequirements, "toolCapabilityRequirements");
        allowedTools = Set.copyOf(allowedTools);
        taintRules = List.copyOf(taintRules);
        automata = List.copyOf(automata);
        grantedCapabilities = Set.copyOf(grantedCapabilities);
        // Map.copyOf doesn't deep-copy the value sets — wrap each.
        var copiedReqs = new java.util.LinkedHashMap<String, Set<String>>(
                toolCapabilityRequirements.size());
        for (var entry : toolCapabilityRequirements.entrySet()) {
            copiedReqs.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        toolCapabilityRequirements = Map.copyOf(copiedReqs);
    }

    /**
     * Phase-1 4-arg shim — preserved for callers that don't yet declare
     * capability data. Defaults both new fields to empty.
     */
    public Policy(String name,
                  Set<String> allowedTools,
                  List<TaintRule> taintRules,
                  List<SecurityAutomaton> automata) {
        this(name, allowedTools, taintRules, automata, Set.of(), Map.of());
    }

    /**
     * Convenience: build a Policy that only declares an allowlist (the
     * common Phase 1 case).
     */
    public static Policy allowlist(String name, String... tools) {
        return new Policy(name, Set.of(tools), List.of(), List.of());
    }

    /**
     * Returns a copy of this policy with the supplied capability grants
     * and tool-requirement map.
     */
    public Policy withCapabilities(Set<String> grants,
                                    Map<String, Set<String>> requirements) {
        return new Policy(name, allowedTools, taintRules, automata,
                grants, requirements);
    }
}
