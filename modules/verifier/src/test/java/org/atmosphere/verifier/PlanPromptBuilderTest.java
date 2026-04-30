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
package org.atmosphere.verifier;

import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.prompt.PlanPromptBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPromptBuilderTest {

    @Test
    void promptListsOnlyPolicyAllowedTools() {
        // Registry has all three fixture tools; policy permits two.
        Policy policy = PlanFixtures.policyAllowing(
                PlanFixtures.FETCH, PlanFixtures.SUMMARIZE);
        var builder = new PlanPromptBuilder(policy, PlanFixtures.registryWithFixtureTools());

        String prompt = builder.build("Summarize my inbox");

        assertTrue(prompt.contains(PlanFixtures.FETCH),
                () -> "permitted tool missing: " + prompt);
        assertTrue(prompt.contains(PlanFixtures.SUMMARIZE),
                () -> "permitted tool missing: " + prompt);
        assertFalse(prompt.contains(PlanFixtures.SEND),
                () -> "non-permitted tool leaked into prompt: " + prompt);
    }

    @Test
    void unknownPermittedToolDoesNotCrashOrLeak() {
        // Policy mentions a tool the registry doesn't know about — the
        // verifier will still flag it, but the prompt builder must not
        // advertise an entry it can't describe.
        Policy policy = Policy.allowlist("test", "ghost_tool");
        var builder = new PlanPromptBuilder(policy, PlanFixtures.registryWithFixtureTools());

        String prompt = builder.build("anything");

        assertFalse(prompt.contains("ghost_tool"),
                () -> "ghost tool advertised without registry definition: " + prompt);
        // Empty permitted list still produces a usable prompt
        assertTrue(prompt.contains("Goal: anything"));
    }

    @Test
    void emptyPolicyEmitsRefusalMarker() {
        var builder = new PlanPromptBuilder(
                PlanFixtures.policyAllowing(),
                PlanFixtures.registryWithFixtureTools());

        String prompt = builder.build("do anything");

        assertTrue(prompt.contains("(none — refuse to plan)"),
                () -> "empty policy did not produce refusal marker: " + prompt);
    }

    @Test
    void promptEmbedsSchemaInstructions() {
        var builder = new PlanPromptBuilder(
                PlanFixtures.policyAllowing(PlanFixtures.FETCH),
                PlanFixtures.registryWithFixtureTools());

        String prompt = builder.build("g");

        // Schema instructions name the wire-format field 'goal' and 'steps'.
        // If those rename, this test catches the drift loudly.
        assertTrue(prompt.contains("goal"), () -> "schema 'goal' missing");
        assertTrue(prompt.contains("steps"), () -> "schema 'steps' missing");
    }

    @Test
    void promptIncludesUserGoalVerbatim() {
        var builder = new PlanPromptBuilder(
                PlanFixtures.policyAllowing(PlanFixtures.FETCH),
                PlanFixtures.registryWithFixtureTools());
        String goal = "Find the meaning of life — and CC bob@x";

        String prompt = builder.build(goal);

        assertTrue(prompt.endsWith("Goal: " + goal + "\n"),
                () -> "user goal not at tail of prompt: " + prompt);
    }

    @Test
    void promptIsDeterministicAcrossInvocations() {
        var builder = new PlanPromptBuilder(
                PlanFixtures.policyAllowing(PlanFixtures.FETCH, PlanFixtures.SUMMARIZE),
                PlanFixtures.registryWithFixtureTools());

        // Same inputs → identical output; supports prompt-caching upstream
        // and snapshot-based regression detection.
        assertEquals(builder.build("g"), builder.build("g"));
    }
}
