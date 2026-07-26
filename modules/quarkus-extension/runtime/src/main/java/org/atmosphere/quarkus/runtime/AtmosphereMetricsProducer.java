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
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
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
        // Record whether a Prometheus-backed registry actually booted — the
        // exact condition under which quarkus-micrometer-registry-prometheus
        // serves its export route. AtmosphereConsoleInfoServlet reads this to
        // report hasMetrics to the console (Runtime Truth — Invariant #5).
        // Runs before the framework check: the export route is live even when
        // the AtmosphereServlet was never reached during boot.
        if (containsPrometheusRegistry(registry)) {
            ConsoleMetricsState.markPrometheusLive();
        }
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

    /**
     * Whether the given registry is (or contains, for composites) a
     * Prometheus-backed registry. Matched by class name — never a compile-time
     * import — because the Prometheus registry jar is not a dependency of this
     * module: it only appears when the user app pulls
     * {@code quarkus-micrometer-registry-prometheus}. Both the simpleclient
     * ({@code io.micrometer.prometheus}) and the newer client
     * ({@code io.micrometer.prometheusmetrics}) packages are recognized.
     *
     * @param registry the registry to inspect, may be {@code null}
     * @return {@code true} when a Prometheus registry is present
     */
    static boolean containsPrometheusRegistry(MeterRegistry registry) {
        if (registry == null) {
            return false;
        }
        if (registry instanceof CompositeMeterRegistry composite) {
            for (MeterRegistry member : composite.getRegistries()) {
                if (containsPrometheusRegistry(member)) {
                    return true;
                }
            }
            return false;
        }
        for (Class<?> c = registry.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            var name = c.getName();
            if ("io.micrometer.prometheus.PrometheusMeterRegistry".equals(name)
                    || "io.micrometer.prometheusmetrics.PrometheusMeterRegistry".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
