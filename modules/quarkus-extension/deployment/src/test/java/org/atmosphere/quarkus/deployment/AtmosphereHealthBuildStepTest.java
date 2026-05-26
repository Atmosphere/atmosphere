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

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.quarkus.runtime.AtmosphereHealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the SmallRye Health {@code @BuildStep} (Spring Boot parity for
 * {@code AtmosphereActuatorAutoConfiguration}). Boots Quarkus with
 * {@code quarkus-smallrye-health} on the classpath, asserts the
 * {@link AtmosphereHealthCheck} bean was registered and the check returns
 * {@code UP} with the expected payload fields. The test would FAIL without
 * {@code AtmosphereProcessor.registerHealthCheck} because Arc would not
 * have any {@code HealthCheck} bean named {@code atmosphere}.
 */
public class AtmosphereHealthBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereHealthBuildStepTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    @Liveness
    AtmosphereHealthCheck healthCheck;

    @Test
    public void healthCheckReportsUp() {
        assertNotNull(healthCheck,
                "AtmosphereHealthCheck must be CDI-resolvable via AtmosphereProcessor.registerHealthCheck");
        HealthCheckResponse response = healthCheck.call();
        assertEquals("atmosphere", response.getName(),
                "Health check name must mirror Spring Boot's AtmosphereHealthIndicator");
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "Framework is alive — should report UP");
        assertTrue(response.getData().isPresent(), "Health payload must include data");
        var data = response.getData().get();
        assertTrue(data.containsKey("version"),
                "Health payload must include 'version' (got " + data.keySet() + ")");
        assertTrue(data.containsKey("status"),
                "Health payload must include 'status'");
    }
}
