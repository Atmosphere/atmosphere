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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link AbstractAgentRuntime#effectiveModel} — the model-resolution
 * fallback the built-in runtime uses when it builds a request. Regression for
 * a real bug: long-term-memory fact extraction builds an
 * {@link AgentExecutionContext} with a {@code null} model, and the built-in
 * runtime sent that {@code null} straight to the provider (empty/failed
 * completion, silently no memory). The stub runtimes used by the memory unit
 * tests ignored the context, so only an end-to-end run caught it. The context
 * model must win when set, otherwise the configured {@link AiConfig} model.
 */
class EffectiveModelFallbackTest {

    @AfterEach
    void reset() {
        AiConfig.resetForTesting();
    }

    private static AgentExecutionContext contextWithModel(String model) {
        return new AgentExecutionContext(
                "extract facts", "system", model, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null,
                (org.atmosphere.ai.approval.ApprovalStrategy) null);
    }

    @Test
    void nullContextModelFallsBackToConfiguredModel() {
        AiConfig.configure("fake", "configured-model", null, null);

        var resolved = AbstractAgentRuntime.effectiveModel(contextWithModel(null), null);

        assertEquals("configured-model", resolved,
                "a null context model must fall back to the AiConfig model, not stay null");
    }

    @Test
    void explicitContextModelWins() {
        AiConfig.configure("fake", "configured-model", null, null);

        var resolved = AbstractAgentRuntime.effectiveModel(contextWithModel("endpoint-model"), null);

        assertEquals("endpoint-model", resolved,
                "an explicit context model wins over the configured default");
    }

    @Test
    void fallsBackToAdapterDefaultWhenNothingConfigured() {
        AiConfig.resetForTesting();

        var resolved = AbstractAgentRuntime.effectiveModel(contextWithModel(null), "adapter-default");

        assertEquals("adapter-default", resolved,
                "with no context model and no AiConfig, the adapter default is used");
    }
}
