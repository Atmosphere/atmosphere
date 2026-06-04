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

import java.util.List;

import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.metrics.AtmosphereTracing;
import org.atmosphere.quarkus.runtime.AtmosphereTracingProducer;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the OpenTelemetry tracing {@code @BuildStep} (Spring Boot
 * parity for {@code AtmosphereTracingAutoConfiguration}). Boots Quarkus
 * with {@code quarkus-opentelemetry} forced on the test classpath (via
 * {@link io.quarkus.test.AbstractQuarkusExtensionTest#setForcedDependencies},
 * scoping the dep to this test only so the reactor-wide test classpath
 * doesn't get polluted with OTel deps that conflict with Micrometer's
 * Prometheus exemplar provider on its OTel 1.55.0 baseline). Asserts the
 * {@link AtmosphereTracingProducer} fired during {@code StartupEvent} and
 * the {@link AtmosphereTracing} interceptor is now in the framework's
 * interceptor list. Would FAIL without
 * {@code AtmosphereProcessor.registerTracingProducer}.
 */
public class AtmosphereTracingBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereTracingBuildStepTest.class))
            .setForcedDependencies(otelDeps())
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0")
            // Disable the OTel exporters in test to avoid network side-effects.
            .overrideConfigKey("quarkus.otel.traces.exporter", "none")
            .overrideConfigKey("quarkus.otel.metrics.exporter", "none")
            .overrideConfigKey("quarkus.otel.logs.exporter", "none");

    @Inject
    AtmosphereTracingProducer producer;

    private static List<Dependency> otelDeps() {
        return List.of(
                new ArtifactDependency("io.quarkus", "quarkus-opentelemetry",
                        null, "jar", System.getProperty("quarkus.version", "3.36.0")));
    }

    @Test
    public void tracingInterceptorInstalled() {
        assertNotNull(producer, "AtmosphereTracingProducer must be CDI-resolvable");
        assertNotNull(producer.installed(),
                "AtmosphereTracing must be installed during StartupEvent — null indicates "
                        + "AtmosphereProcessor.registerTracingProducer did not fire");
        // The interceptor is on the framework's interceptor chain.
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework);
        boolean tracingInChain = framework.interceptors().stream()
                .anyMatch(i -> i instanceof AtmosphereTracing);
        assertTrue(tracingInChain,
                "AtmosphereTracing interceptor must be in the framework interceptor chain "
                        + "after AtmosphereTracingProducer.onStart() — chain="
                        + framework.interceptors());
    }
}
