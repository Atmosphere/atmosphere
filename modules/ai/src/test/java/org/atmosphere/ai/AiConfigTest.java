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
import static org.junit.jupiter.api.Assertions.*;

public class AiConfigTest {

    @Test
    public void testResolveBaseUrlLocal() {
        assertEquals(AiConfig.OLLAMA_ENDPOINT, AiConfig.resolveBaseUrl("local", null, "llama3.2"));
    }

    @Test
    public void testResolveBaseUrlExplicitWins() {
        assertEquals("https://custom.api/v1", AiConfig.resolveBaseUrl("remote", "https://custom.api/v1", "any"));
    }

    @Test
    public void testResolveBaseUrlGeminiDefault() {
        assertEquals(AiConfig.GEMINI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "gemini-2.5-flash"));
    }

    @Test
    public void testResolveBaseUrlOpenAiGpt() {
        assertEquals(AiConfig.OPENAI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "gpt-4o"));
    }

    @Test
    public void testResolveBaseUrlOpenAiO1() {
        assertEquals(AiConfig.OPENAI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "o1-mini"));
    }

    @Test
    public void testResolveBaseUrlOpenAiO3() {
        assertEquals(AiConfig.OPENAI_ENDPOINT, AiConfig.resolveBaseUrl("remote", null, "o3"));
    }

    @Test
    public void testResolveBaseUrlBlankExplicit() {
        assertEquals(AiConfig.GEMINI_ENDPOINT, AiConfig.resolveBaseUrl("remote", "", "gemini-2.5-flash"));
    }

    @Test
    public void testConfigureSetsInstance() {
        var settings = AiConfig.configure("local", "llama3.2", null, null);

        assertNotNull(settings);
        assertEquals("llama3.2", settings.model());
        assertEquals("local", settings.mode());
        assertTrue(settings.isLocal());
        assertEquals(AiConfig.OLLAMA_ENDPOINT, settings.baseUrl());
        assertSame(AiConfig.get(), settings);
    }

    @Test
    public void testConfigureRemoteNotLocal() {
        var settings = AiConfig.configure("remote", "gemini-2.5-flash", "test-key", null);

        assertFalse(settings.isLocal());
        assertEquals(AiConfig.GEMINI_ENDPOINT, settings.baseUrl());
    }

    @Test
    public void testDefaultConstants() {
        assertEquals("gemini-2.5-flash", AiConfig.DEFAULT_MODEL);
        assertEquals("remote", AiConfig.DEFAULT_MODE);
    }

    @Test
    public void testInitParamConstants() {
        assertEquals("org.atmosphere.ai.llmMode", AiConfig.LLM_MODE);
        assertEquals("org.atmosphere.ai.llmModel", AiConfig.LLM_MODEL);
        assertEquals("org.atmosphere.ai.llmApiKey", AiConfig.LLM_API_KEY);
        assertEquals("org.atmosphere.ai.llmBaseUrl", AiConfig.LLM_BASE_URL);
    }

    @Test
    public void testPromptCacheKeyModeDefaultsToAuto() {
        // No sysprop set: the resolved mode and the settings component both
        // default to AUTO (byte-identical legacy behavior).
        var previous = System.getProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        System.clearProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        try {
            assertEquals(org.atmosphere.ai.llm.PromptCacheKeyMode.AUTO,
                    AiConfig.resolvePromptCacheKeyMode());
            var settings = AiConfig.configure("local", "llama3.2", null, null);
            assertEquals(org.atmosphere.ai.llm.PromptCacheKeyMode.AUTO,
                    settings.promptCacheKeyMode());
        } finally {
            restoreProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, previous);
        }
    }

    @Test
    public void testPromptCacheKeyModeEnabledFromSysprop() {
        var previous = System.getProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "true");
        try {
            assertEquals(org.atmosphere.ai.llm.PromptCacheKeyMode.ENABLED,
                    AiConfig.resolvePromptCacheKeyMode());
            var settings = AiConfig.configure("remote", "gpt-4o", "k", "https://api.openai.com/v1");
            assertEquals(org.atmosphere.ai.llm.PromptCacheKeyMode.ENABLED,
                    settings.promptCacheKeyMode());
        } finally {
            restoreProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, previous);
        }
    }

    @Test
    public void testPromptCacheKeyModeDisabledFromSysprop() {
        var previous = System.getProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "0");
        try {
            assertEquals(org.atmosphere.ai.llm.PromptCacheKeyMode.DISABLED,
                    AiConfig.resolvePromptCacheKeyMode());
            var settings = AiConfig.configure("remote", "gpt-4o", "k", "https://api.openai.com/v1");
            assertEquals(org.atmosphere.ai.llm.PromptCacheKeyMode.DISABLED,
                    settings.promptCacheKeyMode());
        } finally {
            restoreProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, previous);
        }
    }

    @Test
    public void testPromptCacheKeyModeMalformedFallsBackToAuto() {
        var previous = System.getProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY);
        System.setProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, "not-a-real-value");
        try {
            assertEquals(org.atmosphere.ai.llm.PromptCacheKeyMode.AUTO,
                    AiConfig.resolvePromptCacheKeyMode());
        } finally {
            restoreProperty(AiConfig.PROMPT_CACHE_KEY_PROPERTY, previous);
        }
    }

    // -- Generation parameters (B2) --

    @Test
    public void testFourArgShimDefaultsGenerationToDefaults() {
        // The 4-arg shim must default the generation component to
        // GenerationParams.defaults() (all null) — backward compat.
        var settings = new AiConfig.LlmSettings(
                new org.atmosphere.ai.llm.FakeLlmClient("m"), "m", "fake", null);
        assertNotNull(settings.generation());
        assertTrue(settings.generation().isEmpty(),
                "4-arg shim must default generation to unset");
        assertSame(GenerationParams.defaults(), settings.generation());
    }

    @Test
    public void testFiveArgShimDefaultsGenerationToDefaults() {
        var settings = new AiConfig.LlmSettings(
                new org.atmosphere.ai.llm.FakeLlmClient("m"), "m", "fake", null, "key");
        assertNotNull(settings.generation());
        assertTrue(settings.generation().isEmpty(),
                "5-arg shim must default generation to unset");
    }

    @Test
    public void testSixArgShimDefaultsGenerationToDefaults() {
        var settings = new AiConfig.LlmSettings(
                new org.atmosphere.ai.llm.FakeLlmClient("m"), "m", "fake", null, "key",
                org.atmosphere.ai.llm.PromptCacheKeyMode.AUTO);
        assertNotNull(settings.generation());
        assertTrue(settings.generation().isEmpty(),
                "6-arg shim must default generation to unset");
    }

    @Test
    public void testGenerationDefaultsWhenNoKnobsSet() {
        // No sysprops set: configure() must store GenerationParams.defaults()
        // so the wire stays byte-identical to the pre-feature behavior.
        withCleared(() -> {
            assertTrue(AiConfig.resolveGenerationParams().isEmpty());
            var settings = AiConfig.configure("local", "llama3.2", null, null);
            assertTrue(settings.generation().isEmpty());
        });
    }

    @Test
    public void testGenerationParsesAllFourKnobsFromSysprops() {
        // configure() reads the same sysprops fromEnvironment() funnels through,
        // so this exercises the env-knob parse path (env vars cannot be set
        // in-process; the sysprop is the higher-precedence source configure reads).
        withCleared(() -> {
            System.setProperty(AiConfig.TEMPERATURE_PROPERTY, "0.3");
            System.setProperty(AiConfig.MAX_TOKENS_PROPERTY, "512");
            System.setProperty(AiConfig.TOP_P_PROPERTY, "0.9");
            System.setProperty(AiConfig.STOP_PROPERTY, "STOP, END ,,DONE");

            var gen = AiConfig.resolveGenerationParams();
            assertEquals(0.3, gen.temperature());
            assertEquals(512, gen.maxTokens());
            assertEquals(0.9, gen.topP());
            // blank entry between END and DONE is dropped, entries trimmed
            assertEquals(java.util.List.of("STOP", "END", "DONE"), gen.stop());

            var settings = AiConfig.configure("local", "llama3.2", null, null);
            assertEquals(0.3, settings.generation().temperature());
            assertEquals(512, settings.generation().maxTokens());
            assertEquals(0.9, settings.generation().topP());
            assertEquals(java.util.List.of("STOP", "END", "DONE"), settings.generation().stop());
        });
    }

    @Test
    public void testGenerationMalformedNumericIgnoredNotThrown() {
        withCleared(() -> {
            System.setProperty(AiConfig.TEMPERATURE_PROPERTY, "not-a-number");
            System.setProperty(AiConfig.MAX_TOKENS_PROPERTY, "abc");
            System.setProperty(AiConfig.TOP_P_PROPERTY, "");
            // Must not throw; malformed values resolve to unset.
            var gen = assertDoesNotThrow(AiConfig::resolveGenerationParams);
            assertNull(gen.temperature(), "malformed temperature ignored");
            assertNull(gen.maxTokens(), "malformed max-tokens ignored");
            assertNull(gen.topP(), "blank top-p ignored");
            assertTrue(gen.isEmpty());
        });
    }

    @Test
    public void testGenerationNonPositiveMaxTokensDropped() {
        withCleared(() -> {
            System.setProperty(AiConfig.MAX_TOKENS_PROPERTY, "-5");
            var gen = AiConfig.resolveGenerationParams();
            assertNull(gen.maxTokens(), "non-positive max-tokens dropped at the boundary");
        });
    }

    @Test
    public void testGenerationOutOfRangeClampedNotThrown() {
        withCleared(() -> {
            System.setProperty(AiConfig.TEMPERATURE_PROPERTY, "50");
            System.setProperty(AiConfig.TOP_P_PROPERTY, "9");
            var gen = AiConfig.resolveGenerationParams();
            assertEquals(2.0, gen.temperature(), "temperature clamped to provider max");
            assertEquals(1.0, gen.topP(), "top-p clamped to 1.0");
        });
    }

    @Test
    public void testGenerationKnobConstants() {
        assertEquals("atmosphere.ai.temperature", AiConfig.TEMPERATURE_PROPERTY);
        assertEquals("LLM_TEMPERATURE", AiConfig.TEMPERATURE_ENV);
        assertEquals("atmosphere.ai.max-tokens", AiConfig.MAX_TOKENS_PROPERTY);
        assertEquals("LLM_MAX_TOKENS", AiConfig.MAX_TOKENS_ENV);
        assertEquals("atmosphere.ai.top-p", AiConfig.TOP_P_PROPERTY);
        assertEquals("LLM_TOP_P", AiConfig.TOP_P_ENV);
        assertEquals("atmosphere.ai.stop", AiConfig.STOP_PROPERTY);
        assertEquals("LLM_STOP", AiConfig.STOP_ENV);
    }

    // -- installClient seam (F3a) --

    @Test
    public void testInstallClientSwapsClientPreservingOtherComponents() {
        // configure a baseline, then install a replacement client and assert
        // ONLY the client component changed.
        var base = AiConfig.configure("remote", "gemini-2.5-flash", "test-key",
                "https://api.openai.com/v1");
        var baseClient = base.client();
        var replacement = new org.atmosphere.ai.llm.FakeLlmClient("router-model");

        var swapped = AiConfig.installClient(replacement);

        assertSame(replacement, swapped.client(), "installed client must be the new one");
        assertSame(replacement, AiConfig.get().client(),
                "AiConfig.get() must reflect the installed client");
        assertNotSame(baseClient, AiConfig.get().client(),
                "the previous client must be replaced");
        // Every other component preserved verbatim.
        assertEquals(base.model(), swapped.model());
        assertEquals(base.mode(), swapped.mode());
        assertEquals(base.baseUrl(), swapped.baseUrl());
        assertEquals(base.apiKey(), swapped.apiKey());
        assertEquals(base.promptCacheKeyMode(), swapped.promptCacheKeyMode());
        assertSame(base.generation(), swapped.generation());
    }

    @Test
    public void testInstallClientBeforeConfigureThrows() {
        // Reset the singleton to the unconfigured state so this test is
        // order-independent: installClient with no resolved settings must throw.
        AiConfig.resetForTesting();
        var replacement = new org.atmosphere.ai.llm.FakeLlmClient("router-model");
        assertThrows(IllegalStateException.class, () -> AiConfig.installClient(replacement));
    }

    @Test
    public void testInstallClientNullThrows() {
        AiConfig.configure("local", "llama3.2", null, null);
        assertThrows(NullPointerException.class, () -> AiConfig.installClient(null));
    }

    /**
     * Run {@code body} with all four generation sysprops cleared, restoring any
     * prior values afterwards so the tests do not leak global state.
     */
    private static void withCleared(Runnable body) {
        var keys = new String[]{
                AiConfig.TEMPERATURE_PROPERTY, AiConfig.MAX_TOKENS_PROPERTY,
                AiConfig.TOP_P_PROPERTY, AiConfig.STOP_PROPERTY};
        var prior = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            prior[i] = System.getProperty(keys[i]);
            System.clearProperty(keys[i]);
        }
        try {
            body.run();
        } finally {
            for (int i = 0; i < keys.length; i++) {
                restoreProperty(keys[i], prior[i]);
            }
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
