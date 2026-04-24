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
package org.atmosphere.spring.boot;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.GovernanceMetrics;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer-backed {@link GovernanceMetrics} implementation.
 *
 * <p>Publishes two meters under the {@code atmosphere.governance.*} namespace,
 * both per-policy tagged for drill-down:</p>
 *
 * <ul>
 *   <li>{@code atmosphere.governance.scope.similarity} — DistributionSummary of
 *       similarity scores, tagged {@code policy}, {@code tier}, {@code decision}.
 *       Scale 0..1 (the scope guardrail output); NaN values (error paths) are
 *       dropped so they don't skew the histogram.</li>
 *   <li>{@code atmosphere.governance.policy.evaluation} — Timer of per-policy
 *       {@code evaluate()} latency, tagged {@code policy}, {@code decision}.</li>
 * </ul>
 *
 * <p>Meters are built once per (tagged) combination and cached — re-creating
 * them on every record call would thrash Micrometer's registry-level lookup
 * map and the admission path is hot enough that it matters.</p>
 */
public final class MicrometerGovernanceMetrics implements GovernanceMetrics {

    public static final String SIMILARITY_METER = "atmosphere.governance.scope.similarity";
    public static final String EVALUATION_METER = "atmosphere.governance.policy.evaluation";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, DistributionSummary> similarityMeters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> evaluationMeters = new ConcurrentHashMap<>();

    public MicrometerGovernanceMetrics(MeterRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
    }

    @Override
    public void recordSimilarity(String policyName, AgentScope.Tier tier,
                                 String decision, double similarity) {
        if (Double.isNaN(similarity)) {
            return;
        }
        var safePolicy = policyName == null ? "unknown" : policyName;
        var safeTier = tier == null ? "unspecified" : tier.name();
        var safeDecision = decision == null ? "unspecified" : decision;
        var key = safePolicy + '|' + safeTier + '|' + safeDecision;
        var summary = similarityMeters.computeIfAbsent(key, k ->
                DistributionSummary.builder(SIMILARITY_METER)
                        .description("Scope guardrail similarity score distribution")
                        .baseUnit("ratio")
                        .tags("policy", safePolicy,
                              "tier", safeTier,
                              "decision", safeDecision)
                        .publishPercentiles(0.5, 0.9, 0.99)
                        .register(registry));
        summary.record(similarity);
    }

    @Override
    public void recordEvaluationLatency(String policyName, String decision, double evaluationMs) {
        var safePolicy = policyName == null ? "unknown" : policyName;
        var safeDecision = decision == null ? "unspecified" : decision;
        var key = safePolicy + '|' + safeDecision;
        var timer = evaluationMeters.computeIfAbsent(key, k ->
                Timer.builder(EVALUATION_METER)
                        .description("GovernancePolicy.evaluate() latency")
                        .tags("policy", safePolicy, "decision", safeDecision)
                        .publishPercentiles(0.5, 0.9, 0.99)
                        .register(registry));
        timer.record((long) (evaluationMs * 1_000_000.0), java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
