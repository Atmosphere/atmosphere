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

import org.atmosphere.quarkus.runtime.AtmosphereDurableRunsProducer;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the tri-state bridging of the agent-harness app-wide switch: an
 * explicit {@code quarkus.atmosphere.ai.harness.enabled=false} must reach the
 * framework init-param as the literal {@code "false"} — the kill switch that
 * beats every annotation — rather than being dropped like an unset value, and
 * it must not imply the durable-run spine.
 */
public class HarnessKillSwitchBridgeTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(HarnessKillSwitchBridgeTest.class))
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.atmosphere.ai.harness.enabled", "false");

    @Inject
    AtmosphereDurableRunsProducer producer;

    @Test
    public void explicitFalseBridgesAsTheKillSwitch() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        assertEquals("false",
                framework.getAtmosphereConfig().getInitParameter("org.atmosphere.ai.harness.enabled"),
                "an explicit harness.enabled=false must bridge as the literal \"false\" kill switch");
    }

    @Test
    public void killSwitchDoesNotImplyTheDurableRunSpine() {
        assertNotNull(producer, "AtmosphereDurableRunsProducer must be CDI-resolvable");
        assertFalse(producer.installed(),
                "harness.enabled=false must not install the durable-run spine");
    }
}
