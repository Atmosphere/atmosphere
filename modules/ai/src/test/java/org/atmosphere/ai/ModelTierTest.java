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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Resolver-only unit tests for {@link ModelTier}. Uses the same well-known
 * endpoint constants as {@link AiConfigTest} so provider detection matches the
 * runtime-resolved base URLs.
 */
public class ModelTierTest {

    // (a) Raw model strings pass through byte-for-byte — the backward-compat invariant.
    @Test
    public void testRawModelStringPassesThroughUnchanged() {
        assertEquals("gpt-4o", ModelTier.resolve("gpt-4o", AiConfig.OPENAI_ENDPOINT, "x"));
        assertEquals("claude-sonnet-4-6",
                ModelTier.resolve("claude-sonnet-4-6", AiConfig.OPENAI_ENDPOINT, "x"));
        assertEquals("gemini-2.5-flash",
                ModelTier.resolve("gemini-2.5-flash", AiConfig.GEMINI_ENDPOINT, "x"));
        // A string that merely contains a tier word but is not exactly a tier token.
        assertEquals("fast-model", ModelTier.resolve("fast-model", AiConfig.OPENAI_ENDPOINT, "x"));
    }

    // (b) Provider-neutral: same alias maps to different concretes per provider.
    @Test
    public void testFrontierIsProviderNeutral() {
        assertEquals("gpt-4o", ModelTier.resolve("frontier", AiConfig.OPENAI_ENDPOINT, "x"));
        assertEquals("gemini-2.5-pro", ModelTier.resolve("frontier", AiConfig.GEMINI_ENDPOINT, "x"));
    }

    @Test
    public void testFastAndReasoningPerProvider() {
        assertEquals("gpt-4o-mini", ModelTier.resolve("fast", AiConfig.OPENAI_ENDPOINT, "x"));
        assertEquals("gemini-2.5-flash", ModelTier.resolve("fast", AiConfig.GEMINI_ENDPOINT, "x"));
        assertEquals("o3", ModelTier.resolve("reasoning", AiConfig.OPENAI_ENDPOINT, "x"));
        // Ollama provider detected from the well-known local endpoint.
        assertEquals("llama3.2", ModelTier.resolve("fast", AiConfig.OLLAMA_ENDPOINT, "x"));
    }

    // (c) Case-insensitivity and surrounding whitespace are tolerated.
    @Test
    public void testCaseAndWhitespaceInsensitive() {
        assertEquals("gpt-4o-mini", ModelTier.resolve("FAST", AiConfig.OPENAI_ENDPOINT, "x"));
        assertEquals("o3", ModelTier.resolve(" reasoning ", AiConfig.OPENAI_ENDPOINT, "x"));
        assertEquals("gpt-4o", ModelTier.resolve("Frontier", AiConfig.OPENAI_ENDPOINT, "x"));
    }

    // (d) null/blank pass through unchanged (passthrough).
    @Test
    public void testNullAndBlankPassThrough() {
        assertEquals("", ModelTier.resolve("", AiConfig.OPENAI_ENDPOINT, "x"));
        assertNull(ModelTier.resolve(null, AiConfig.OPENAI_ENDPOINT, "x"));
        assertEquals("   ", ModelTier.resolve("   ", AiConfig.OPENAI_ENDPOINT, "x"));
    }

    // (e) Unmapped provider/tier returns the fallback and never throws.
    @Test
    public void testUnmappedProviderReturnsFallbackNeverThrows() {
        assertEquals("fallback-model",
                ModelTier.resolve("frontier", "https://unknown.example.com/v1", "fallback-model"));
        // A null base URL means provider unknown → fallback.
        assertEquals("fallback-model",
                ModelTier.resolve("fast", null, "fallback-model"));
        // Even when the fallback itself is null, resolve must not throw.
        assertDoesNotThrow(() -> ModelTier.resolve("reasoning", "https://unknown.example.com", null));
        assertNull(ModelTier.resolve("reasoning", "https://unknown.example.com", null));
    }

    // (f) System-property override wins over the built-in table.
    @Test
    public void testSystemPropertyOverrideWins() {
        var key = ModelTier.OVERRIDE_PREFIX + "openai.frontier";
        var previous = System.getProperty(key);
        try {
            System.setProperty(key, "gpt-4.1-custom");
            assertEquals("gpt-4.1-custom",
                    ModelTier.resolve("frontier", AiConfig.OPENAI_ENDPOINT, "x"));
            // Other tiers/providers remain on the built-in table.
            assertEquals("gpt-4o-mini",
                    ModelTier.resolve("fast", AiConfig.OPENAI_ENDPOINT, "x"));
            assertEquals("gemini-2.5-pro",
                    ModelTier.resolve("frontier", AiConfig.GEMINI_ENDPOINT, "x"));
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    public void testIsTier() {
        assertTrue(ModelTier.isTier("fast"));
        assertTrue(ModelTier.isTier(" FRONTIER "));
        assertTrue(ModelTier.isTier("Reasoning"));
        assertFalse(ModelTier.isTier("gpt-4o"));
        assertFalse(ModelTier.isTier(""));
        assertFalse(ModelTier.isTier(null));
    }
}
