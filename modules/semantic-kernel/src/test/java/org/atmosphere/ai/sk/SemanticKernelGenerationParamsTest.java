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
package org.atmosphere.ai.sk;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.GenerationParams;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Semantic Kernel Runtime-Truth proof (Correctness Invariant #5) for the
 * framework {@link GenerationParams}:
 * <ul>
 *   <li>{@code temperature}/{@code maxTokens}/{@code topP}/{@code stop} reach
 *       the {@code PromptExecutionSettings} the runtime attaches to its
 *       default {@code InvocationContext} ({@code withTemperature}/
 *       {@code withMaxTokens}/{@code withTopP}/{@code withStopSequences})
 *       when set.</li>
 *   <li>When nothing is set and no native schema is present, NO
 *       {@code PromptExecutionSettings} is attached at all — dispatch stays
 *       byte-identical to the pre-{@code GenerationParams} behavior.</li>
 *   <li>Generation overrides and native structured output attach
 *       independently — a schema does not suppress the sampling knobs and
 *       vice versa.</li>
 * </ul>
 * No wire capture exists for SK's Azure-SDK client, so the assertions pin
 * the built {@code PromptExecutionSettings} object — the exact input SK's
 * {@code OpenAIChatCompletion} maps onto its wire request.
 */
class SemanticKernelGenerationParamsTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}
            """;

    @AfterEach
    public void tearDown() {
        // Reset AiConfig singleton so generation state does not leak.
        AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    void generationReachesPromptExecutionSettingsWhenSet() {
        var generation = new GenerationParams(0.33, 444, 0.55, List.of("STOP", "HALT"));
        var invocation = SemanticKernelAgentRuntime.buildInvocationContext(false, null, generation);

        var settings = invocation.getPromptExecutionSettings();
        assertNotNull(settings, "generation overrides must attach PromptExecutionSettings");
        assertEquals(0.33, settings.getTemperature(), 1e-9);
        assertEquals(444, settings.getMaxTokens());
        assertEquals(0.55, settings.getTopP(), 1e-9);
        assertEquals(List.of("STOP", "HALT"), settings.getStopSequences());
    }

    @Test
    void unsetGenerationAttachesNoPromptExecutionSettings() {
        var invocation = SemanticKernelAgentRuntime.buildInvocationContext(
                false, null, GenerationParams.defaults());
        assertNull(invocation.getPromptExecutionSettings(),
                "unset generation + no schema must attach NO PromptExecutionSettings — "
                        + "dispatch stays byte-identical");
    }

    @Test
    void generationAndNativeSchemaAttachIndependently() {
        var generation = new GenerationParams(0.2, null, null, null);
        var invocation = SemanticKernelAgentRuntime.buildInvocationContext(
                false, SCHEMA, generation);

        var settings = invocation.getPromptExecutionSettings();
        assertNotNull(settings);
        assertEquals(0.2, settings.getTemperature(), 1e-9);
        assertNotNull(settings.getResponseFormat(),
                "native structured-output response format must still be attached");
        // SK API constraint: PromptExecutionSettings.Builder.build() force-fills
        // DEFAULT_MAX_TOKENS (256) for any settings object — an unset knob
        // cannot stay absent once settings attach (same pre-existing behavior
        // as the schema-only path). Pinned here so an SK upgrade that changes
        // the default is caught.
        assertEquals(256, settings.getMaxTokens(),
                "SK force-fills its DEFAULT_MAX_TOKENS when settings attach");
    }

    @Test
    void twoArgOverloadReadsGenerationFromAiConfig() {
        // Drives the REAL production entry point (the two-arg overload the
        // dispatch path calls) so the AiConfig threading is exercised, not a
        // re-implementation.
        installGeneration(new GenerationParams(0.9, 128, null, null));
        var invocation = SemanticKernelAgentRuntime.buildInvocationContext(false, null);

        var settings = invocation.getPromptExecutionSettings();
        assertNotNull(settings, "AiConfig generation must ride the two-arg overload");
        assertEquals(0.9, settings.getTemperature(), 1e-9);
        assertEquals(128, settings.getMaxTokens());
    }

    @Test
    void twoArgOverloadStaysBareWhenAiConfigGenerationUnset() {
        installGeneration(GenerationParams.defaults());
        var invocation = SemanticKernelAgentRuntime.buildInvocationContext(false, null);
        assertNull(invocation.getPromptExecutionSettings(),
                "defaults() via AiConfig must leave the InvocationContext bare");
    }

    // -- helpers --

    private static void installGeneration(GenerationParams generation) {
        var settings = AiConfig.configure("remote", "gpt-4o-mini", "test-key", null);
        try {
            var f = AiConfig.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, new AiConfig.LlmSettings(
                    settings.client(), settings.model(), settings.mode(), settings.baseUrl(),
                    settings.apiKey(), settings.promptCacheKeyMode(), generation));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not install AiConfig settings", e);
        }
    }
}
