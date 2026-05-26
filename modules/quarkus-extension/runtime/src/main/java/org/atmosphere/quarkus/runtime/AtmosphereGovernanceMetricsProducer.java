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
package org.atmosphere.quarkus.runtime;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.GovernanceMetrics;
import org.atmosphere.ai.governance.GovernanceMetricsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Quarkus port of
 * {@code org.atmosphere.spring.boot.AtmosphereGovernanceMetricsAutoConfiguration}.
 *
 * <p>When both {@code quarkus-micrometer} and {@code atmosphere-ai} are on
 * the classpath, the deployment processor registers this bean. On startup
 * the {@link MeterRegistry} bean is wrapped in a Micrometer-backed
 * {@link GovernanceMetrics} implementation and installed in
 * {@link GovernanceMetricsHolder} so every governance policy evaluation
 * publishes a per-policy timer and similarity histogram under the
 * {@code atmosphere.governance.*} namespace.</p>
 *
 * <p>{@link #onShutdown(ShutdownEvent)} resets the holder so a Quarkus
 * dev-mode live reload does not leave a stale registry reference behind —
 * symmetric to {@code DisposableBean.destroy()} in the Spring Boot
 * configuration.</p>
 */
@ApplicationScoped
public class AtmosphereGovernanceMetricsProducer {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereGovernanceMetricsProducer.class);

    /** Distribution summary meter name (mirrors Spring Boot for cross-platform parity). */
    public static final String SIMILARITY_METER = "atmosphere.governance.scope.similarity";

    /** Timer meter name (mirrors Spring Boot for cross-platform parity). */
    public static final String EVALUATION_METER = "atmosphere.governance.policy.evaluation";

    @Inject
    MeterRegistry registry;

    private volatile QuarkusMicrometerGovernanceMetrics installed;

    /**
     * Installs the Micrometer-backed governance metrics implementation on
     * application startup.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires
     *              the observer eagerly)
     */
    public void onStart(@Observes @Priority(110) StartupEvent event) {
        if (installed != null) {
            return;
        }
        installed = new QuarkusMicrometerGovernanceMetrics(registry);
        GovernanceMetricsHolder.install(installed);
        logger.info("Atmosphere governance metrics installed on Quarkus Micrometer registry={}",
                registry.getClass().getSimpleName());
    }

    /**
     * Resets {@link GovernanceMetricsHolder} on shutdown to keep dev-mode
     * live reload from leaking the previous {@link MeterRegistry}.
     *
     * @param event the Quarkus shutdown event (unused, present so Arc fires
     *              the observer)
     */
    public void onShutdown(@Observes ShutdownEvent event) {
        if (installed != null) {
            GovernanceMetricsHolder.reset();
            installed = null;
            logger.debug("Atmosphere governance metrics reset on shutdown");
        }
    }

    /**
     * Accessor used by tests to confirm the install fired during startup.
     *
     * @return the installed instance, or {@code null} if startup has not run yet
     */
    public GovernanceMetrics installed() {
        return installed;
    }

    /**
     * Quarkus-side Micrometer adapter for {@link GovernanceMetrics}. Mirrors
     * {@code MicrometerGovernanceMetrics} in the Spring Boot starter so the
     * two platforms publish meters under identical names and tags — cross-
     * platform dashboards (Grafana, etc.) work without rewriting queries.
     */
    static final class QuarkusMicrometerGovernanceMetrics implements GovernanceMetrics {

        private final MeterRegistry registry;
        private final ConcurrentHashMap<String, DistributionSummary> similarityMeters =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Timer> evaluationMeters = new ConcurrentHashMap<>();

        QuarkusMicrometerGovernanceMetrics(MeterRegistry registry) {
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
            String safePolicy = policyName == null ? "unknown" : policyName;
            String safeTier = tier == null ? "unspecified" : tier.name();
            String safeDecision = decision == null ? "unspecified" : decision;
            String key = safePolicy + '|' + safeTier + '|' + safeDecision;
            DistributionSummary summary = similarityMeters.computeIfAbsent(key, k ->
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
        public void recordEvaluationLatency(String policyName, String decision,
                                            double evaluationMs) {
            String safePolicy = policyName == null ? "unknown" : policyName;
            String safeDecision = decision == null ? "unspecified" : decision;
            String key = safePolicy + '|' + safeDecision;
            Timer timer = evaluationMeters.computeIfAbsent(key, k ->
                    Timer.builder(EVALUATION_METER)
                            .description("GovernancePolicy.evaluate() latency")
                            .tags("policy", safePolicy, "decision", safeDecision)
                            .publishPercentiles(0.5, 0.9, 0.99)
                            .register(registry));
            timer.record((long) (evaluationMs * 1_000_000.0), TimeUnit.NANOSECONDS);
        }
    }
}
