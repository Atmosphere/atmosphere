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
 * <p>The same record can feed Atmosphere's runtime {@code GovernancePolicy}
 * chain, so a single declaration enforces at both layers — defense in depth
 * from one source.</p>
 *
 * <p>Field consumers:</p>
 * <ul>
 *   <li>{@code allowedTools} — {@code AllowlistVerifier}.</li>
 *   <li>{@code taintRules} — {@code TaintVerifier}.</li>
 *   <li>{@code automata} — {@code AutomatonVerifier}.</li>
 *   <li>{@code grantedCapabilities} +
 *       {@code toolCapabilityRequirements} — {@code CapabilityVerifier}.</li>
 *   <li>{@code numericInvariants} — the SMT-backed {@code SmtChecker}
 *       (e.g. {@code atmosphere-verifier-smt}); proves numeric constraints
 *       over symbolic tool-call arguments.</li>
 *   <li>{@code controlFlow} — {@code StructureVerifier}; selects whether the
 *       plan may contain {@code ConditionalNode} branches.</li>
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
 * @param numericInvariants          numeric constraints proved by the
 *                                   SMT-backed {@code SmtChecker} over symbolic
 *                                   tool-call arguments; defensively copied;
 *                                   may be empty.
 * @param controlFlow                which plan shapes are admitted; defaults to
 *                                   {@link ControlFlowMode#LINEAR_ONLY} when
 *                                   {@code null}.
 */
public record Policy(String name,
                     Set<String> allowedTools,
                     List<TaintRule> taintRules,
                     List<SecurityAutomaton> automata,
                     Set<String> grantedCapabilities,
                     Map<String, Set<String>> toolCapabilityRequirements,
                     List<NumericInvariant> numericInvariants,
                     ControlFlowMode controlFlow) {

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
        Objects.requireNonNull(numericInvariants, "numericInvariants");
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
        numericInvariants = List.copyOf(numericInvariants);
        // Default to the strongest posture: a flat, linear plan.
        controlFlow = controlFlow == null ? ControlFlowMode.LINEAR_ONLY : controlFlow;
    }

    /**
     * Pre-control-flow 7-arg overload — preserved for callers that declare
     * numeric invariants but no control-flow mode. Defaults
     * {@code controlFlow} to {@link ControlFlowMode#LINEAR_ONLY}.
     */
    public Policy(String name,
                  Set<String> allowedTools,
                  List<TaintRule> taintRules,
                  List<SecurityAutomaton> automata,
                  Set<String> grantedCapabilities,
                  Map<String, Set<String>> toolCapabilityRequirements,
                  List<NumericInvariant> numericInvariants) {
        this(name, allowedTools, taintRules, automata, grantedCapabilities,
                toolCapabilityRequirements, numericInvariants, ControlFlowMode.LINEAR_ONLY);
    }

    /**
     * Capability-aware 6-arg overload — preserved for callers that declare
     * capability data but no numeric invariants. Defaults
     * {@code numericInvariants} to empty.
     */
    public Policy(String name,
                  Set<String> allowedTools,
                  List<TaintRule> taintRules,
                  List<SecurityAutomaton> automata,
                  Set<String> grantedCapabilities,
                  Map<String, Set<String>> toolCapabilityRequirements) {
        this(name, allowedTools, taintRules, automata,
                grantedCapabilities, toolCapabilityRequirements, List.of());
    }

    /**
     * Allowlist-plus-rules 4-arg shim — preserved for callers that don't
     * declare capability or numeric-invariant data. Defaults all later
     * fields to empty / {@link ControlFlowMode#LINEAR_ONLY}.
     */
    public Policy(String name,
                  Set<String> allowedTools,
                  List<TaintRule> taintRules,
                  List<SecurityAutomaton> automata) {
        this(name, allowedTools, taintRules, automata, Set.of(), Map.of());
    }

    /**
     * Convenience: build a Policy that only declares an allowlist.
     */
    public static Policy allowlist(String name, String... tools) {
        return new Policy(name, Set.of(tools), List.of(), List.of());
    }

    /**
     * Returns a copy of this policy with the supplied capability grants
     * and tool-requirement map. All other fields are preserved.
     */
    public Policy withCapabilities(Set<String> grants,
                                    Map<String, Set<String>> requirements) {
        return new Policy(name, allowedTools, taintRules, automata,
                grants, requirements, numericInvariants, controlFlow);
    }

    /**
     * Returns a copy of this policy with the supplied numeric invariants,
     * preserving all other fields. Used to attach SMT proof obligations to an
     * existing policy.
     */
    public Policy withNumericInvariants(List<NumericInvariant> invariants) {
        return new Policy(name, allowedTools, taintRules, automata,
                grantedCapabilities, toolCapabilityRequirements, invariants, controlFlow);
    }

    /**
     * Returns a copy of this policy in the supplied control-flow mode,
     * preserving all other fields. Opt into
     * {@link ControlFlowMode#BRANCHING} to admit conditional plans.
     */
    public Policy withControlFlow(ControlFlowMode mode) {
        return new Policy(name, allowedTools, taintRules, automata,
                grantedCapabilities, toolCapabilityRequirements, numericInvariants,
                Objects.requireNonNull(mode, "mode"));
    }
}
