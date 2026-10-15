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

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link AiKnobBridgeTest} on the Vert.x container
 * ({@code quarkus.atmosphere.container=vertx}), which boots the framework
 * without the servlet: the knobs reach {@link AiConfig} there too.
 */
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
public class AiKnobBridgeVertxTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = AiKnobBridgeTest.withEveryKnob(new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AiKnobBridgeVertxTest.class, AiKnobBridgeTest.class))
            .overrideConfigKey("quarkus.atmosphere.container", "vertx")
            .overrideConfigKey("quarkus.http.test-port", "0"));

    @Test
    public void everyKnobSetInTheQuarkusConfigurationIsVisibleThroughAiConfig() {
        assertNotNull(LazyAtmosphereConfigurator.getFramework(),
                "the Atmosphere framework must be initialized at startup");
        for (var knob : AiConfig.knobs()) {
            var env = System.getenv(knob.env());
            var expected = env != null && !env.isBlank()
                    ? env.trim() : AiKnobBridgeTest.valueFor(knob.property());
            assertEquals(expected, AiConfig.knob(knob.property()).orElse(null), knob.property());
        }
        assertEquals("built-in", AgentRuntimeResolver.resolve().name());
    }
}
