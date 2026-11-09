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
package org.atmosphere.ai.identity;

import org.atmosphere.ai.facts.FactResolver;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentFingerprintTest {

    private static final String PROMPT = "You are the billing agent.";

    @Test
    void promptHashIsStableAcrossTurnsWhoseGroundedFactsDiffer() {
        var monday = new FactResolver.FactBundle(Map.of("today", "Monday")).appendToSystemPrompt(PROMPT);
        var tuesday = new FactResolver.FactBundle(Map.of("today", "Tuesday")).appendToSystemPrompt(PROMPT);

        assertNotEquals(monday, tuesday, "the dispatched prompts differ by their facts block");
        assertEquals(AgentFingerprint.systemPromptSha256(PROMPT),
                AgentFingerprint.systemPromptSha256(monday));
        assertEquals(AgentFingerprint.systemPromptSha256(monday),
                AgentFingerprint.systemPromptSha256(tuesday));
    }

    @Test
    void promptHashChangesWhenTheInstructionsChange() {
        assertNotEquals(AgentFingerprint.systemPromptSha256(PROMPT),
                AgentFingerprint.systemPromptSha256(PROMPT + " Never issue refunds."));
    }

    @Test
    void promptHashIsTheSha256OfTheStablePrompt() {
        // printf '%s' "You are the billing agent." | shasum -a 256
        assertEquals("2683da159b9711dfab14c9e576f9f0af6e73540a33898d9d81aa6496356986ed",
                AgentFingerprint.systemPromptSha256(PROMPT));
    }

    @Test
    void toolSetHashIgnoresOrderDuplicatesAndBlanks() {
        assertEquals(AgentFingerprint.toolSetSha256(List.of("a", "b")),
                AgentFingerprint.toolSetSha256(Arrays.asList("b", "a", "a", " ", null)));
        assertNotEquals(AgentFingerprint.toolSetSha256(List.of("a")),
                AgentFingerprint.toolSetSha256(List.of("a", "b")));
    }

    @Test
    void absentComponentsAreLeftOutOfTheAttributes() {
        var fingerprint = AgentFingerprint.of("built-in", " ", null, List.of());

        assertNull(fingerprint.model());
        assertNull(fingerprint.systemPromptSha256());
        assertNull(fingerprint.toolSetSha256());
        assertEquals(Map.of(AgentFingerprint.RUNTIME_ATTRIBUTE, "built-in"), fingerprint.asAttributes());
    }

    @Test
    void factsOnlyPromptHasNoStableVersion() {
        var factsOnly = new FactResolver.FactBundle(Map.of("today", "Monday")).appendToSystemPrompt("");

        assertEquals("", FactResolver.FactBundle.stablePrefix(factsOnly));
        assertNull(AgentFingerprint.systemPromptSha256(factsOnly));
    }
}
