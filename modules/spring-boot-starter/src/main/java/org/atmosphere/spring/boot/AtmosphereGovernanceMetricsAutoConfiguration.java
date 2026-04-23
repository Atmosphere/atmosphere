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

import io.micrometer.core.instrument.MeterRegistry;
import org.atmosphere.ai.governance.GovernanceMetrics;
import org.atmosphere.ai.governance.GovernanceMetricsHolder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that wires a Micrometer-backed {@link GovernanceMetrics}
 * into {@link GovernanceMetricsHolder} when Micrometer is on the classpath.
 *
 * <p>Until this runs, every governance observation routes to
 * {@link GovernanceMetrics#NOOP}. After this runs, similarity histograms and
 * per-policy evaluation timers show up under {@code atmosphere.governance.*}
 * in whatever backend Micrometer is publishing to (Prometheus, OTLP, etc.).</p>
 *
 * <p>{@link #installHolder()} is idempotent — re-installing the same instance
 * is a no-op at the holder level. {@link #uninstallHolder()} on shutdown
 * restores the NOOP so a context refresh doesn't leave a stale registry
 * reference behind.</p>
 */
@AutoConfiguration
@ConditionalOnClass({MeterRegistry.class, GovernanceMetrics.class})
@ConditionalOnBean(MeterRegistry.class)
public class AtmosphereGovernanceMetricsAutoConfiguration implements InitializingBean, DisposableBean {

    private final GovernanceMetrics metrics;

    public AtmosphereGovernanceMetricsAutoConfiguration(MeterRegistry registry) {
        this.metrics = new MicrometerGovernanceMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public GovernanceMetrics governanceMetrics() {
        return metrics;
    }

    @Override
    public void afterPropertiesSet() {
        GovernanceMetricsHolder.install(metrics);
    }

    @Override
    public void destroy() {
        GovernanceMetricsHolder.reset();
    }
}
