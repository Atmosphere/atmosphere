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
package org.atmosphere.ai.plan;

import org.atmosphere.ai.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@link PlanningMode} tri-state knob: lenient parse (unrecognized
 * collapses to AUTO — Correctness Invariant #4) and the
 * {@code atmosphere.ai.planning} sysprop resolution through
 * {@link AiConfig#resolvePlanningMode()}.
 */
public class PlanningModeTest {

    @AfterEach
    public void clearProperty() {
        System.clearProperty(AiConfig.PLANNING_PROPERTY);
    }

    @Test
    public void parseIsLenient() {
        assertEquals(PlanningMode.AUTO, PlanningMode.parse(null));
        assertEquals(PlanningMode.AUTO, PlanningMode.parse(""));
        assertEquals(PlanningMode.AUTO, PlanningMode.parse("  "));
        assertEquals(PlanningMode.AUTO, PlanningMode.parse("auto"));
        assertEquals(PlanningMode.AUTO, PlanningMode.parse("garbage"));
        assertEquals(PlanningMode.BUILTIN, PlanningMode.parse("builtin"));
        assertEquals(PlanningMode.BUILTIN, PlanningMode.parse(" Built-In "));
        assertEquals(PlanningMode.NATIVE, PlanningMode.parse("native"));
        assertEquals(PlanningMode.NATIVE, PlanningMode.parse("NATIVE"));
    }

    @Test
    public void syspropResolvesThroughAiConfig() {
        assertEquals(PlanningMode.AUTO, AiConfig.resolvePlanningMode(),
                "unset must default to AUTO");

        System.setProperty(AiConfig.PLANNING_PROPERTY, "builtin");
        assertEquals(PlanningMode.BUILTIN, AiConfig.resolvePlanningMode());

        System.setProperty(AiConfig.PLANNING_PROPERTY, "native");
        assertEquals(PlanningMode.NATIVE, AiConfig.resolvePlanningMode());

        System.setProperty(AiConfig.PLANNING_PROPERTY, "not-a-mode");
        assertEquals(PlanningMode.AUTO, AiConfig.resolvePlanningMode(),
                "malformed values must collapse to AUTO, never throw");
    }
}
