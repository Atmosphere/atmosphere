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

import org.atmosphere.cpr.AtmosphereConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ModelWindowCatalogTest {

    @Test
    public void knownModelResolvesToItsWindow() {
        assertEquals(128_000, ModelWindowCatalog.contextWindow("gpt-4o"));
        assertEquals(128_000, ModelWindowCatalog.contextWindow("gpt-4o-mini"));
        assertEquals(200_000, ModelWindowCatalog.contextWindow("o3"));
        assertEquals(1_000_000, ModelWindowCatalog.contextWindow("gemini-2.5-flash"));
        assertEquals(1_000_000, ModelWindowCatalog.contextWindow("gemini-2.5-pro"));
        assertEquals(128_000, ModelWindowCatalog.contextWindow("llama3.2"));
    }

    @Test
    public void familyPrefixResolvesVersionedIds() {
        assertEquals(200_000, ModelWindowCatalog.contextWindow("claude-sonnet-4-6"));
        assertEquals(200_000, ModelWindowCatalog.contextWindow("claude-3-5-haiku-20241022"));
        assertEquals(1_000_000, ModelWindowCatalog.contextWindow("gemini-2.5-flash-lite"));
        assertEquals(128_000, ModelWindowCatalog.contextWindow("gpt-4o-2024-08-06"));
        assertEquals(128_000, ModelWindowCatalog.contextWindow("llama3.3"));
    }

    @Test
    public void lookupIsCaseAndWhitespaceInsensitive() {
        assertEquals(200_000, ModelWindowCatalog.contextWindow("  Claude-Sonnet-4-6  "));
    }

    @Test
    public void unknownModelFallsBackToDefault() {
        assertEquals(ModelWindowCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
                ModelWindowCatalog.contextWindow("no-such-model-2099"));
    }

    @Test
    public void nullAndBlankFallBackToDefaultWithoutThrowing() {
        assertEquals(ModelWindowCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
                ModelWindowCatalog.contextWindow(null));
        assertEquals(ModelWindowCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
                ModelWindowCatalog.contextWindow("   "));
    }

    @Test
    public void defaultMatchesHistoricalFlatBudget() {
        // Never worse than today: an unknown model keeps the pre-catalog budget.
        assertEquals(TokenWindowStrategy.DEFAULT_MAX_TOKENS,
                ModelWindowCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS);
    }

    @Test
    public void knownWindowIsPresentOnlyForKnownModels() {
        assertTrue(ModelWindowCatalog.knownWindow("gpt-4o").isPresent());
        assertTrue(ModelWindowCatalog.knownWindow("claude-opus-4-1").isPresent());
        assertFalse(ModelWindowCatalog.knownWindow("no-such-model-2099").isPresent());
        assertFalse(ModelWindowCatalog.knownWindow(null).isPresent());
    }

    @Test
    public void systemPropertyOverrideWins() {
        var key = ModelWindowCatalog.OVERRIDE_PREFIX + "gpt-4o";
        var previous = System.getProperty(key);
        try {
            System.setProperty(key, "131072");
            assertEquals(131_072, ModelWindowCatalog.contextWindow("gpt-4o"));
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    public void configInitParamOverrideWins() {
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(ModelWindowCatalog.OVERRIDE_PREFIX + "gpt-4o"))
                .thenReturn("64000");
        assertEquals(64_000, ModelWindowCatalog.contextWindow(cfg, "gpt-4o"));
    }

    @Test
    public void malformedOverrideIsIgnoredAndFallsBackToBuiltIn() {
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(ModelWindowCatalog.OVERRIDE_PREFIX + "gpt-4o"))
                .thenReturn("not-a-number");
        // Boundary Safety: malformed override does not throw; built-in wins.
        assertEquals(128_000, ModelWindowCatalog.contextWindow(cfg, "gpt-4o"));
    }

    @Test
    public void nonPositiveOverrideIsIgnored() {
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(ModelWindowCatalog.OVERRIDE_PREFIX + "gpt-4o"))
                .thenReturn("0");
        assertEquals(128_000, ModelWindowCatalog.contextWindow(cfg, "gpt-4o"));
    }
}
