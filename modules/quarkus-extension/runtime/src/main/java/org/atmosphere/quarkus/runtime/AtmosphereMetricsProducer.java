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

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.metrics.AtmosphereMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus port of
 * {@code org.atmosphere.spring.boot.AtmosphereMetricsAutoConfiguration}.
 *
 * <p>When {@code quarkus-micrometer} is on the classpath the deployment
 * processor registers this bean via {@code AdditionalBeanBuildItem}; on
 * Quarkus startup the {@code MeterRegistry} bean is already initialized so
 * {@link AtmosphereMetrics#install(AtmosphereFramework, MeterRegistry)}
 * binds Atmosphere's per-resource gauges, broadcast counters and timers
 * under the {@code atmosphere.*} namespace.</p>
 *
 * <p>The {@link AtmosphereFramework} reference is resolved lazily through
 * {@link LazyAtmosphereConfigurator#getFramework()} rather than via CDI
 * injection so we don't collide with the Atmosphere-runtime
 * {@code AtmosphereProducers#getAtmosphereFramework} producer (an ambiguous
 * resolution would break Arc deployment-time bean wiring).</p>
 */
@ApplicationScoped
public class AtmosphereMetricsProducer {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereMetricsProducer.class);

    @Inject
    MeterRegistry registry;

    private volatile AtmosphereMetrics installed;

    /**
     * Eagerly installs metrics binding on application startup. Priority
     * 100 keeps us after Quarkus' core Micrometer initialisation but
     * before user-application bootstrap.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires
     *              the observer eagerly)
     */
    public void onStart(@Observes @Priority(100) StartupEvent event) {
        if (installed != null) {
            return;
        }
        AtmosphereFramework framework = LazyAtmosphereConfigurator.getFramework();
        if (framework == null) {
            logger.warn("Atmosphere framework not yet available at StartupEvent — "
                    + "metrics installation skipped. This typically means the "
                    + "AtmosphereServlet was never reached during boot "
                    + "(loadOnStartup<=0 or no @AiEndpoint / @ManagedService classes).");
            return;
        }
        installed = AtmosphereMetrics.install(framework, registry);
        logger.info("Atmosphere Micrometer metrics installed on Quarkus registry={}",
                registry.getClass().getSimpleName());
    }

    /**
     * Accessor used by tests to confirm the install fired during startup.
     *
     * @return the installed instance, or {@code null} if startup has not run yet
     */
    public AtmosphereMetrics installed() {
        return installed;
    }
}
