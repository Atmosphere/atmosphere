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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.atmosphere.ai.annotation.AgentScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrometerGovernanceMetricsTest {

    @Test
    void recordSimilarityRegistersTaggedSummary() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerGovernanceMetrics(registry);

        metrics.recordSimilarity("scope.support", AgentScope.Tier.EMBEDDING_SIMILARITY, "admit", 0.82);

        var summary = registry.find(MicrometerGovernanceMetrics.SIMILARITY_METER)
                .tag("policy", "scope.support")
                .tag("tier", "EMBEDDING_SIMILARITY")
                .tag("decision", "admit")
                .summary();
        assertNotNull(summary);
        assertEquals(1, summary.count());
        assertEquals(0.82, summary.mean(), 1e-6);
    }

    @Test
    void recordEvaluationLatencyRegistersTaggedTimer() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerGovernanceMetrics(registry);

        metrics.recordEvaluationLatency("scope.support", "deny", 3.5);

        var timer = registry.find(MicrometerGovernanceMetrics.EVALUATION_METER)
                .tag("policy", "scope.support")
                .tag("decision", "deny")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 3.0);
    }

    @Test
    void similarityNaNIsDroppedSoHistogramStaysClean() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerGovernanceMetrics(registry);

        metrics.recordSimilarity("scope.support", AgentScope.Tier.EMBEDDING_SIMILARITY, "error", Double.NaN);

        var summary = registry.find(MicrometerGovernanceMetrics.SIMILARITY_METER).summary();
        assertNull(summary);
    }

    @Test
    void metersAreCachedAcrossCallsWithTheSameTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerGovernanceMetrics(registry);

        metrics.recordSimilarity("p", AgentScope.Tier.RULE_BASED, "admit", 0.1);
        metrics.recordSimilarity("p", AgentScope.Tier.RULE_BASED, "admit", 0.2);
        metrics.recordSimilarity("p", AgentScope.Tier.RULE_BASED, "admit", 0.3);

        var summary = registry.find(MicrometerGovernanceMetrics.SIMILARITY_METER)
                .tag("policy", "p")
                .tag("tier", "RULE_BASED")
                .tag("decision", "admit")
                .summary();
        assertEquals(3, summary.count());
    }

    @Test
    void distinctTagCombinationsProduceDistinctMeters() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerGovernanceMetrics(registry);

        metrics.recordSimilarity("p1", AgentScope.Tier.RULE_BASED, "admit", 0.1);
        metrics.recordSimilarity("p1", AgentScope.Tier.RULE_BASED, "deny", 0.9);
        metrics.recordSimilarity("p2", AgentScope.Tier.EMBEDDING_SIMILARITY, "admit", 0.5);

        assertEquals(3, registry.find(MicrometerGovernanceMetrics.SIMILARITY_METER).summaries().size());
    }

    @Test
    void nullPolicyAndDecisionCoerceToSafeDefaults() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerGovernanceMetrics(registry);

        metrics.recordSimilarity(null, null, null, 0.4);
        metrics.recordEvaluationLatency(null, null, 1.0);

        assertNotNull(registry.find(MicrometerGovernanceMetrics.SIMILARITY_METER)
                .tag("policy", "unknown")
                .tag("tier", "unspecified")
                .tag("decision", "unspecified")
                .summary());
        assertNotNull(registry.find(MicrometerGovernanceMetrics.EVALUATION_METER)
                .tag("policy", "unknown")
                .tag("decision", "unspecified")
                .timer());
    }

    @Test
    void rejectsNullRegistry() {
        assertThrows(IllegalArgumentException.class, () -> new MicrometerGovernanceMetrics(null));
    }
}
