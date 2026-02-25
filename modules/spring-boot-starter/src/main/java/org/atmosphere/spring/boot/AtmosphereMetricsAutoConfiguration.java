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
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.metrics.AtmosphereMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that wires Atmosphere's Micrometer metrics when both
 * {@link MeterRegistry} and {@link AtmosphereFramework} are available.
 *
 * <p>Publishes gauges, counters and timers under the {@code atmosphere.*} namespace.
 * See {@link AtmosphereMetrics} for the full list of metrics.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass({MeterRegistry.class, AtmosphereMetrics.class})
@ConditionalOnBean({AtmosphereFramework.class, MeterRegistry.class})
public class AtmosphereMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereMetrics atmosphereMetrics(AtmosphereFramework framework,
                                               MeterRegistry registry) {
        return AtmosphereMetrics.install(framework, registry);
    }
}
