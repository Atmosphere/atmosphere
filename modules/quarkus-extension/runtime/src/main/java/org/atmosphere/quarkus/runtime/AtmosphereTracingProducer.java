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

import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.metrics.AtmosphereTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus port of
 * {@code org.atmosphere.spring.boot.AtmosphereTracingAutoConfiguration}.
 *
 * <p>When {@code quarkus-opentelemetry} is on the classpath the deployment
 * processor registers this bean. On startup the {@link OpenTelemetry} bean
 * is resolved and bound as an interceptor on the Atmosphere framework so
 * every request lifecycle (inspect/suspend/broadcast/disconnect) gets
 * traced under the {@code atmosphere.*} span namespace.</p>
 *
 * <p>The framework is looked up via {@link LazyAtmosphereConfigurator}
 * rather than injected — see {@link AtmosphereMetricsProducer} for the same
 * rationale (avoid an ambiguous resolution with
 * {@code AtmosphereProducers#getAtmosphereFramework}).</p>
 */
@ApplicationScoped
public class AtmosphereTracingProducer {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereTracingProducer.class);

    @Inject
    OpenTelemetry openTelemetry;

    private volatile AtmosphereTracing installed;

    /**
     * Binds the Atmosphere tracing interceptor on application startup.
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
                    + "tracing installation skipped.");
            return;
        }
        AtmosphereTracing tracing = new AtmosphereTracing(openTelemetry);
        framework.interceptor(tracing);
        installed = tracing;
        logger.info("Atmosphere OpenTelemetry tracing interceptor installed");
    }

    /**
     * Accessor used by tests to confirm the install fired during startup.
     *
     * @return the installed instance, or {@code null} if startup has not run yet
     */
    public AtmosphereTracing installed() {
        return installed;
    }
}
