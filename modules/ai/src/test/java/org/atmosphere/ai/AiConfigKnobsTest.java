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
package org.atmosphere.ai;

import org.atmosphere.ai.plan.PlanningMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code atmosphere.ai.*} knobs are one list, and a host's configuration
 * reaches every one of them below the JVM system property and the
 * environment variable.
 */
class AiConfigKnobsTest {

    @AfterEach
    void tearDown() {
        AiConfig.resetHostConfig();
        System.clearProperty(AiConfig.TEMPERATURE_PROPERTY);
        System.clearProperty(AiConfig.PLANNING_PROPERTY);
    }

    /** A knob constant missing from the list could not be set from a host's configuration. */
    @Test
    void everyKnobConstantIsInTheAuthoritativeList() throws Exception {
        var constants = new ArrayList<String>();
        for (var field : AiConfig.class.getDeclaredFields()) {
            var modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && field.getType() == String.class
                    && field.get(null) instanceof String value
                    && value.startsWith("atmosphere.ai.")) {
                constants.add(value);
            }
        }
        assertTrue(constants.size() >= 11, "knob constants found: " + constants);
        var listed = new HashSet<>(AiConfig.knobProperties());
        assertEquals(List.of(), constants.stream().filter(c -> !listed.contains(c)).toList(),
                "atmosphere.ai.* constants missing from AiConfig.knobs()");
        assertEquals(AiConfig.knobs().size(), listed.size(), "knob properties are unique");
        assertEquals(AiConfig.knobs().size(),
                new HashSet<>(AiConfig.knobs().stream().map(AiConfig.Knob::env).toList()).size(),
                "knob environment variables are unique");
    }

    @Test
    void theHostConfigurationReachesEveryKnob() {
        AiConfig.installHostConfig(property -> "host:" + property);
        for (var knob : AiConfig.knobs()) {
            var env = System.getenv(knob.env());
            var expected = env != null && !env.isBlank() ? env.trim() : "host:" + knob.property();
            assertEquals(expected, AiConfig.knob(knob.property()).orElse(null), knob.property());
        }
    }

    @Test
    void theSystemPropertyWinsOverTheHostConfiguration() {
        AiConfig.installHostConfig(Map.of(AiConfig.TEMPERATURE_PROPERTY, "0.2")::get);
        assertEquals(0.2, AiConfig.resolveGenerationParams().temperature(),
                "the host configuration sets the knob when nothing else does");

        System.setProperty(AiConfig.TEMPERATURE_PROPERTY, "0.9");
        assertEquals(0.9, AiConfig.resolveGenerationParams().temperature(),
                "-D" + AiConfig.TEMPERATURE_PROPERTY + " wins over the host configuration");
    }

    @Test
    void theTriStateKnobsReadTheHostConfiguration() {
        AiConfig.installHostConfig(Map.of(AiConfig.PLANNING_PROPERTY, " builtin ")::get);
        assertEquals(PlanningMode.BUILTIN, AiConfig.resolvePlanningMode());
    }

    @Test
    void aHostConfigurationOnlyUninstallsItself() {
        var first = AiConfig.installHostConfig(Map.of(AiConfig.TEMPERATURE_PROPERTY, "0.1")::get);
        var second = AiConfig.installHostConfig(Map.of(AiConfig.TEMPERATURE_PROPERTY, "0.3")::get);

        first.close();
        assertEquals("0.3", AiConfig.knob(AiConfig.TEMPERATURE_PROPERTY).orElse(null),
                "closing a replaced installation leaves the current one");

        second.close();
        second.close();
        assertTrue(AiConfig.knob(AiConfig.TEMPERATURE_PROPERTY).isEmpty()
                        || System.getenv(AiConfig.TEMPERATURE_ENV) != null,
                "closing the current installation removes it");
    }

    @Test
    void onlyKnobsAreKept() {
        var seen = new ArrayList<String>();
        var installed = AiConfig.installHostConfig(property -> {
            seen.add(property);
            return "x";
        });
        assertEquals(AiConfig.knobProperties(), seen);
        assertEquals(new HashSet<>(AiConfig.knobProperties()), installed.values().keySet());
        assertThrows(IllegalArgumentException.class, () -> AiConfig.knob("atmosphere.ai.unknown"));
    }
}
