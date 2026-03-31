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

import org.atmosphere.ai.AiCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@link AdkAgentRuntime} capabilities.
 *
 * <p>ADK requires a real Gemini API key and Runner for execution tests,
 * so this validates only the static contract (capabilities, name, availability).
 * Full execution tests are covered by {@link AdkEventAdapterTest}.</p>
 */
class AdkRuntimeContractTest {

    private AdkAgentRuntime createRuntime() {
        return new AdkAgentRuntime();
    }

    @Test
    void runtimeHasNonBlankName() {
        assertNotNull(createRuntime().name());
        assertFalse(createRuntime().name().isBlank());
    }

    @Test
    void runtimeIsAvailableOnClasspath() {
        assertTrue(createRuntime().isAvailable());
    }

    @Test
    void declaresMinimumCapabilities() {
        var caps = createRuntime().capabilities();
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING));
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT));
    }

    @Test
    void adkDeclaresToolApprovalCapability() {
        var runtime = createRuntime();
        assertTrue(runtime.capabilities().contains(AiCapability.TOOL_APPROVAL));
    }

    @Test
    void adkDeclaresConversationMemory() {
        var runtime = createRuntime();
        assertTrue(runtime.capabilities().contains(AiCapability.CONVERSATION_MEMORY));
    }

    @Test
    void adkDeclaresAgentOrchestration() {
        var runtime = createRuntime();
        assertTrue(runtime.capabilities().contains(AiCapability.AGENT_ORCHESTRATION));
    }
}
