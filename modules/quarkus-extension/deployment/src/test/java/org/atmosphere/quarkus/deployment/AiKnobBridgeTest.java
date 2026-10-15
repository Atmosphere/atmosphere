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
 * Quarkus half of the knob parity pin (Spring side: the starters'
 * {@code AiKnobBridgeTest}): every {@code atmosphere.ai.*} knob set in the
 * Quarkus configuration reaches {@link AiConfig}, and
 * {@code atmosphere.ai.runtime} selects the {@code AgentRuntime}. Walks the
 * authoritative {@link AiConfig#knobProperties()} list, so a knob added to
 * {@code AiConfig} is covered here too.
 */
public class AiKnobBridgeTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = withEveryKnob(new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AiKnobBridgeTest.class))
            .overrideConfigKey("quarkus.http.test-port", "0"));

    static QuarkusExtensionTest withEveryKnob(QuarkusExtensionTest test) {
        for (var property : AiConfig.knobProperties()) {
            test.overrideConfigKey(property, valueFor(property));
        }
        return test;
    }

    static String valueFor(String property) {
        // The demo runtime outranks the built-in one when no key is configured,
        // so selecting built-in proves the knob, not the priority, chose it.
        return AiConfig.RUNTIME_PROPERTY.equals(property) ? "built-in" : "value-of-" + property;
    }

    @Test
    public void everyKnobSetInTheQuarkusConfigurationIsVisibleThroughAiConfig() {
        assertNotNull(LazyAtmosphereConfigurator.getFramework(),
                "the Atmosphere framework must be initialized at startup");
        for (var knob : AiConfig.knobs()) {
            var env = System.getenv(knob.env());
            var expected = env != null && !env.isBlank() ? env.trim() : valueFor(knob.property());
            assertEquals(expected, AiConfig.knob(knob.property()).orElse(null), knob.property());
        }
    }

    @Test
    public void theRuntimePropertySelectsTheAgentRuntime() {
        assertEquals("built-in", AgentRuntimeResolver.resolve().name());
    }
}
