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
package org.atmosphere.ai.governance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite {@link GovernancePolicy} that evaluates wrapped policies in
 * concentric rings, cheapest first, short-circuiting on the first terminal
 * decision. Purpose-built for stacks where a rule-based scope check can
 * reject 80% of traffic in sub-millisecond time before paying for a
 * 100-500 ms LLM-classifier tier.
 *
 * <h2>Evaluation order</h2>
 * <ol>
 *   <li>Ring 1 (outermost / cheapest): rule-based, regex, keyword</li>
 *   <li>Ring 2: embedding-similarity, hash lookups, cached classifiers</li>
 *   <li>Ring 3 (innermost / most expensive): LLM-classifier, remote RAG scans</li>
 * </ol>
 *
 * <p>Ring indices are operator-defined integers — lower = evaluated first.
 * Within a ring, policies evaluate in insertion order. A
 * {@link PolicyDecision.Deny} from any ring terminates evaluation; a
 * {@link PolicyDecision.Transform} rewrites the request and the next ring
 * sees the rewritten form; {@link PolicyDecision.Admit} moves on.</p>
 *
 * <p>Error isolation: a policy that throws in one ring is treated as
 * fail-closed — evaluation stops with a Deny (same semantics as the
 * {@code AiPipeline}'s existing per-policy error handling). Ring-level
 * isolation is intentionally minimal; operators who want "log and continue"
 * wrap the errant policy in {@link DryRunPolicy} first.</p>
 */
public final class PolicyRing implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final List<RingEntry> ringsInOrder;

    private PolicyRing(String name, String source, String version, List<RingEntry> ringsInOrder) {
        this.name = name;
        this.source = source;
        this.version = version;
        this.ringsInOrder = List.copyOf(ringsInOrder);
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var current = context;
        for (var ring : ringsInOrder) {
            for (var policy : ring.policies()) {
                var decision = policy.evaluate(current);
                switch (decision) {
                    case PolicyDecision.Deny ignored -> {
                        return decision;
                    }
                    case PolicyDecision.Transform transform ->
                            current = new PolicyContext(
                                    current.phase(),
                                    transform.modifiedRequest(),
                                    current.accumulatedResponse());
                    case PolicyDecision.Admit ignored -> {
                        // continue to the next policy / ring
                    }
                }
            }
        }
        // Every ring admitted (or transformed). If the final context differs
        // from the input, surface the transform so callers see the rewrite;
        // otherwise plain Admit keeps the return shape predictable.
        if (current.request() != context.request()) {
            return PolicyDecision.transform(current.request());
        }
        return PolicyDecision.admit();
    }

    /** Immutable view of the configured rings, sorted by ring index. */
    public List<RingEntry> rings() {
        return ringsInOrder;
    }

    /** One ring level — the index + the ordered policies at that level. */
    public record RingEntry(int index, List<GovernancePolicy> policies) {
        public RingEntry {
            if (policies == null || policies.isEmpty()) {
                throw new IllegalArgumentException("ring " + index + " must carry at least one policy");
            }
            policies = List.copyOf(policies);
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Fluent builder — accumulates (ringIndex → policies) then sorts on build. */
    public static final class Builder {
        private final String name;
        private String source = "code:" + PolicyRing.class.getName();
        private String version = "1";
        private final Map<Integer, List<GovernancePolicy>> byRing = new LinkedHashMap<>();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            this.name = name;
        }

        public Builder source(String source) {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("source must not be blank");
            }
            this.source = source;
            return this;
        }

        public Builder version(String version) {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("version must not be blank");
            }
            this.version = version;
            return this;
        }

        public Builder ring(int index, GovernancePolicy... policies) {
            return ring(index, List.of(policies));
        }

        public Builder ring(int index, Collection<GovernancePolicy> policies) {
            if (policies == null || policies.isEmpty()) {
                throw new IllegalArgumentException("ring " + index + " policies must be non-empty");
            }
            for (var policy : policies) {
                if (policy == null) {
                    throw new IllegalArgumentException("policies must not contain null");
                }
            }
            byRing.computeIfAbsent(index, k -> new ArrayList<>()).addAll(policies);
            return this;
        }

        public PolicyRing build() {
            if (byRing.isEmpty()) {
                throw new IllegalStateException("PolicyRing needs at least one ring");
            }
            var ordered = new ArrayList<RingEntry>(byRing.size());
            byRing.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> ordered.add(new RingEntry(e.getKey(), e.getValue())));
            return new PolicyRing(name, source, version, ordered);
        }
    }
}
