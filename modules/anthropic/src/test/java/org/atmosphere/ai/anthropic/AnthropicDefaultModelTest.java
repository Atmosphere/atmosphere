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
package org.atmosphere.ai.anthropic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for the configurable default model. Prior to this change
 * the Anthropic default was hardcoded to a provider model string in the source;
 * it is now {@code claude-sonnet-4-6} and overridable without code via the
 * {@code anthropic.model} system property. Per-request and {@code AiConfig}
 * models still win over this fallback (verified through the contract TCK's
 * explicit-model contexts), so this test pins only the fallback resolution.
 */
class AnthropicDefaultModelTest {

    @Test
    void defaultModelIsSonnet46WhenUnconfigured() {
        assertEquals("claude-sonnet-4-6", new AnthropicAgentRuntime().defaultModel());
    }

    @Test
    void anthropicModelSystemPropertyOverridesDefault() {
        var previous = System.getProperty("anthropic.model");
        System.setProperty("anthropic.model", "claude-haiku-4-5");
        try {
            assertEquals("claude-haiku-4-5", new AnthropicAgentRuntime().defaultModel());
        } finally {
            if (previous == null) {
                System.clearProperty("anthropic.model");
            } else {
                System.setProperty("anthropic.model", previous);
            }
        }
    }
}
