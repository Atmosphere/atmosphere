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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.quarkus.runtime.AtmosphereMetricsProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Micrometer metrics {@code @BuildStep} (Spring Boot parity
 * for {@code AtmosphereMetricsAutoConfiguration}). Boots Quarkus with
 * {@code quarkus-micrometer} on the classpath, asserts the
 * {@link AtmosphereMetricsProducer} fired during {@code StartupEvent} and
 * the {@link MeterRegistry} now contains at least one
 * {@code atmosphere.*}-namespaced meter. The test would FAIL without
 * {@code AtmosphereProcessor.registerMetricsProducer} because Arc would
 * never instantiate the producer and no atmosphere meters would be
 * published.
 */
public class AtmosphereMetricsBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereMetricsBuildStepTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    AtmosphereMetricsProducer producer;

    @Inject
    MeterRegistry registry;

    @Test
    public void metricsInstalledOnRegistry() {
        assertNotNull(producer, "AtmosphereMetricsProducer must be CDI-resolvable");
        assertNotNull(producer.installed(),
                "AtmosphereMetrics must be installed during StartupEvent — null indicates "
                        + "AtmosphereProcessor.registerMetricsProducer did not fire");

        List<Meter> atmosphereMeters = registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("atmosphere."))
                .toList();
        assertTrue(!atmosphereMeters.isEmpty(),
                "Registry must contain atmosphere.* meters once AtmosphereMetrics.install() "
                        + "has run (registry meters=" + registry.getMeters().size()
                        + " atmosphere meters=" + atmosphereMeters.size() + ")");
    }
}
