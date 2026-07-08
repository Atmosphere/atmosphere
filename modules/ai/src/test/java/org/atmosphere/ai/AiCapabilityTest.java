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

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCapabilityTest {

    @Test
    void allExpectedCapabilitiesExist() {
        var expected = new String[]{
                "TEXT_STREAMING", "TOOL_CALLING", "STRUCTURED_OUTPUT",
                "VISION", "AUDIO", "MULTI_MODAL", "CONVERSATION_MEMORY",
                "SYSTEM_PROMPT", "AGENT_ORCHESTRATION", "TOOL_APPROVAL",
                "PROMPT_CACHING", "CANCELLATION",
                "MODEL_ENUMERATION", "TOKEN_USAGE", "TOOL_CALL_DELTA",
                "PER_REQUEST_RETRY", "PLANNING", "VIRTUAL_FILESYSTEM"
        };
        for (String name : expected) {
            assertNotNull(AiCapability.valueOf(name), name + " should exist");
        }
    }

    @Test
    void totalCapabilityCount() {
        // 16 baseline + 3 (BUDGET_ENFORCEMENT, CONFIDENCE_SCORES, PASSIVATION)
        // added for the predictable-AI primitives + NATIVE_STRUCTURED_OUTPUT
        // (provider-enforced JSON-schema structured output) + 2 deep-agent
        // native-delegation capabilities (PLANNING, VIRTUAL_FILESYSTEM).
        // MULTI_AGENT_HANDOFF was dropped as redundant with AGENT_ORCHESTRATION
        // (no runtime declared it). Bump this number when a new capability
        // lands; the test exists so capability changes are reviewed
        // deliberately rather than slipped in without thought.
        assertEquals(22, AiCapability.values().length);
    }

    @Test
    void valueOfRoundTrips() {
        for (AiCapability cap : AiCapability.values()) {
            assertEquals(cap, AiCapability.valueOf(cap.name()));
        }
    }

    @Test
    void canBeUsedInEnumSet() {
        var set = EnumSet.of(AiCapability.TEXT_STREAMING, AiCapability.TOOL_CALLING);
        assertTrue(set.contains(AiCapability.TEXT_STREAMING));
        assertTrue(set.contains(AiCapability.TOOL_CALLING));
        assertEquals(2, set.size());
    }

    @Test
    void textStreamingIsFirstDeclared() {
        assertEquals(0, AiCapability.TEXT_STREAMING.ordinal());
    }

    @Test
    void enumSetOfAllHasCorrectSize() {
        var all = EnumSet.allOf(AiCapability.class);
        assertEquals(AiCapability.values().length, all.size());
    }
}
