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
import com.google.adk.runner.Runner;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.GenerationParams;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADK Runtime-Truth proof (Correctness Invariant #5) for the framework
 * {@link GenerationParams}:
 * <ul>
 *   <li>{@code temperature}/{@code maxTokens}/{@code topP}/{@code stop} reach
 *       the agent's {@code GenerateContentConfig} (as {@code temperature}/
 *       {@code maxOutputTokens}/{@code topP}/{@code stopSequences}) when set —
 *       on both the {@code createNativeClient} path and the per-request
 *       {@code buildRequestRunner} dispatch path (Invariant #7 — Mode
 *       Parity).</li>
 *   <li>When nothing is set, NO {@code GenerateContentConfig} is attached at
 *       all, so the wire request stays byte-identical to the
 *       pre-{@code GenerationParams} behavior.</li>
 * </ul>
 */
class AdkGenerationParamsTest {

    @AfterEach
    public void tearDown() {
        // Reset AiConfig singleton so generation state does not leak.
        AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    void generationReachesGenerateContentConfigOnCreateNativeClient() {
        var generation = new GenerationParams(0.33, 444, 0.55, List.of("STOP", "HALT"));
        var runner = new TestableRuntime().create(settings(generation));

        var config = rootAgentConfig(runner);
        assertEquals(0.33f, config.temperature().orElseThrow(), 1e-6f,
                "temperature must map to GenerateContentConfig.temperature (Float)");
        assertEquals(444, config.maxOutputTokens().orElseThrow(),
                "maxTokens must map to GenerateContentConfig.maxOutputTokens");
        assertEquals(0.55f, config.topP().orElseThrow(), 1e-6f,
                "topP must map to GenerateContentConfig.topP (Float)");
        assertEquals(List.of("STOP", "HALT"), config.stopSequences().orElseThrow(),
                "stop must map to GenerateContentConfig.stopSequences");
    }

    @Test
    void unsetGenerationAttachesNoConfigOnCreateNativeClient() {
        var runner = new TestableRuntime().create(settings(GenerationParams.defaults()));
        var agent = assertInstanceOf(LlmAgent.class, runner.agent());
        assertTrue(agent.generateContentConfig().isEmpty(),
                "unset generation must attach NO GenerateContentConfig — "
                        + "wire request stays byte-identical");
    }

    @Test
    void partialGenerationMapsOnlySetComponents() {
        var generation = new GenerationParams(null, 512, null, null);
        var runner = new TestableRuntime().create(settings(generation));

        var config = rootAgentConfig(runner);
        assertTrue(config.temperature().isEmpty(), "unset temperature must stay unset");
        assertEquals(512, config.maxOutputTokens().orElseThrow());
        assertTrue(config.topP().isEmpty(), "unset topP must stay unset");
        assertTrue(config.stopSequences().isEmpty(), "unset stop must stay unset");
    }

    @Test
    void generationReachesPerRequestRunner() {
        // Drives the REAL buildRequestRunner dispatch seam so the per-request
        // tool-calling/streaming path proves the same mapping (Invariant #7).
        installGeneration(new GenerationParams(0.2, 256, 0.9, List.of("END")));
        var runtime = new AdkAgentRuntime();
        var runner = runtime.buildRequestRunner(textContext(), new CollectingSession());

        var config = rootAgentConfig(runner);
        assertEquals(0.2f, config.temperature().orElseThrow(), 1e-6f);
        assertEquals(256, config.maxOutputTokens().orElseThrow());
        assertEquals(0.9f, config.topP().orElseThrow(), 1e-6f);
        assertEquals(List.of("END"), config.stopSequences().orElseThrow());
    }

    @Test
    void unsetGenerationAttachesNoConfigOnPerRequestRunner() {
        installGeneration(GenerationParams.defaults());
        var runtime = new AdkAgentRuntime();
        var runner = runtime.buildRequestRunner(textContext(), new CollectingSession());

        var agent = assertInstanceOf(LlmAgent.class, runner.agent());
        assertTrue(agent.generateContentConfig().isEmpty(),
                "unset generation must attach NO GenerateContentConfig on the "
                        + "per-request runner either");
    }

    // -- helpers --

    private static com.google.genai.types.GenerateContentConfig rootAgentConfig(Runner runner) {
        var agent = assertInstanceOf(LlmAgent.class, runner.agent());
        return agent.generateContentConfig().orElseThrow(
                () -> new AssertionError("generateContentConfig must be attached when set"));
    }

    private static AiConfig.LlmSettings settings(GenerationParams generation) {
        return new AiConfig.LlmSettings(null, "gemini-2.5-flash", "remote", null,
                "test-key", null, generation);
    }

    private static void installGeneration(GenerationParams generation) {
        var settings = AiConfig.configure("remote", "gemini-2.5-flash", "test-key", null);
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

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hi", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /**
     * Exposes the protected {@code createNativeClient} so the wiring tests can
     * drive the REAL generation threading the runtime resolves — not a
     * re-implementation.
     */
    static final class TestableRuntime extends AdkAgentRuntime {
        Runner create(AiConfig.LlmSettings settings) {
            return createNativeClient(settings);
        }
    }
}
