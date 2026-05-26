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
package org.atmosphere.quarkus.deployment;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.GovernanceMetrics;
import org.atmosphere.ai.governance.GovernanceMetricsHolder;
import org.atmosphere.quarkus.runtime.AtmosphereGovernanceMetricsProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the governance metrics {@code @BuildStep} (Spring Boot parity
 * for {@code AtmosphereGovernanceMetricsAutoConfiguration}). Boots Quarkus
 * with {@code quarkus-micrometer} + {@code atmosphere-ai}, asserts the
 * {@link AtmosphereGovernanceMetricsProducer} fired during
 * {@code StartupEvent}, replaced the {@link GovernanceMetricsHolder} NOOP,
 * and exercises the metrics path so per-policy counters land in the
 * {@link MeterRegistry}. The test would FAIL without
 * {@code AtmosphereProcessor.registerGovernanceMetricsProducer} because
 * the holder would stay at {@link GovernanceMetrics#NOOP} and no
 * {@code atmosphere.governance.*} meters would be published.
 */
public class AtmosphereGovernanceMetricsBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereGovernanceMetricsBuildStepTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    AtmosphereGovernanceMetricsProducer producer;

    @Inject
    MeterRegistry registry;

    @Test
    public void governanceMetricsInstalledAndRecorded() {
        assertNotNull(producer, "AtmosphereGovernanceMetricsProducer must be CDI-resolvable");
        assertNotNull(producer.installed(),
                "Producer must have installed a non-NOOP GovernanceMetrics during StartupEvent");
        assertNotSame(GovernanceMetrics.NOOP, producer.installed(),
                "Installed instance must replace the default NOOP");
        // The holder lookup must now return our Quarkus-side impl.
        GovernanceMetrics fromHolder = GovernanceMetricsHolder.get();
        assertEquals(producer.installed(), fromHolder,
                "GovernanceMetricsHolder.get() must return the installed instance");

        // Record one similarity and one latency; the meters must land in the registry.
        fromHolder.recordSimilarity("allowlist", AgentScope.Tier.RULE_BASED, "ALLOW", 0.42);
        fromHolder.recordEvaluationLatency("allowlist", "ALLOW", 1.5);
        boolean similarityMeterFound = registry.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals(
                        AtmosphereGovernanceMetricsProducer.SIMILARITY_METER));
        boolean evaluationMeterFound = registry.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals(
                        AtmosphereGovernanceMetricsProducer.EVALUATION_METER));
        assertTrue(similarityMeterFound,
                "atmosphere.governance.scope.similarity must be in the Micrometer registry "
                        + "after recordSimilarity()");
        assertTrue(evaluationMeterFound,
                "atmosphere.governance.policy.evaluation must be in the Micrometer registry "
                        + "after recordEvaluationLatency()");
    }
}
