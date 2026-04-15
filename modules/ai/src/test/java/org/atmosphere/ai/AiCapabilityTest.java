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
                "PROMPT_CACHING", "MULTI_AGENT_HANDOFF", "CANCELLATION",
                "MODEL_ENUMERATION", "TOKEN_USAGE", "TOOL_CALL_DELTA",
                "PER_REQUEST_RETRY"
        };
        for (String name : expected) {
            assertNotNull(AiCapability.valueOf(name), name + " should exist");
        }
    }

    @Test
    void totalCapabilityCount() {
        assertEquals(17, AiCapability.values().length);
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
