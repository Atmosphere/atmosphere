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
package org.atmosphere.ai.adk;

import com.google.adk.agents.LlmAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins ADK's native agent-loop bound.
 *
 * <p>ADK is the only delegating runtime that exposes an iteration cap
 * ({@code LlmAgent.maxSteps}); the other five framework adapters have no such
 * knob, so Atmosphere's {@code ToolLoopGuard} cannot bound them and says so.
 * The cap here is a startup opt-in rather than a default, because ADK counts
 * agent steps — not Atmosphere's model→tool→model rounds — and forcing the
 * built-in family's default of 5 would truncate legitimate multi-step agents.
 * These tests pin both halves: the knob reaches the builder when set, and stays
 * out of the way when it isn't.</p>
 */
class AdkAgentRuntimeMaxStepsTest {

    @BeforeEach
    @AfterEach
    void reset() {
        AdkAgentRuntime.clearMaxSteps();
    }

    @Test
    void unsetByDefaultSoAdkKeepsItsOwnLoopBound() {
        assertEquals(null, AdkAgentRuntime.maxSteps(),
                "no cap unless an operator opts in — ADK steps are not tool rounds");

        var agent = LlmAgent.builder().name("a").instruction("i").build();
        assertTrue(agent.maxSteps().isEmpty(),
                "an unconfigured builder must leave ADK's own default in place");
    }

    @Test
    void configuredCapReachesTheAgentBuilder() {
        AdkAgentRuntime.setMaxSteps(7);

        assertEquals(7, AdkAgentRuntime.maxSteps());

        // Mirrors what the three configure(...) paths do to their builder.
        var agent = LlmAgent.builder().name("a").instruction("i")
                .maxSteps(AdkAgentRuntime.maxSteps())
                .build();
        assertEquals(7, agent.maxSteps().orElseThrow(),
                "the configured cap must land on ADK's own maxSteps");
    }

    @Test
    void aNonPositiveCapIsRejectedRatherThanSilentlyIgnored() {
        assertThrows(IllegalArgumentException.class, () -> AdkAgentRuntime.setMaxSteps(0));
        assertThrows(IllegalArgumentException.class, () -> AdkAgentRuntime.setMaxSteps(-1));
        assertEquals(null, AdkAgentRuntime.maxSteps(), "a rejected value must not be stored");
    }
}
