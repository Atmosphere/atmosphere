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
package org.atmosphere.ai.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link PromptCacheKeyMode} decision helper and the
 * lenient tri-state parser.
 */
class PromptCacheKeyModeTest {

    @Test
    void resolveEnabledAlwaysEmits() {
        // ENABLED ignores the host heuristic in both directions.
        assertTrue(PromptCacheKeyMode.ENABLED.resolve(true));
        assertTrue(PromptCacheKeyMode.ENABLED.resolve(false));
    }

    @Test
    void resolveDisabledNeverEmits() {
        // DISABLED ignores the host heuristic in both directions.
        assertFalse(PromptCacheKeyMode.DISABLED.resolve(true));
        assertFalse(PromptCacheKeyMode.DISABLED.resolve(false));
    }

    @Test
    void resolveAutoDefersToHeuristic() {
        // AUTO returns the host heuristic verbatim — the byte-identity contract.
        assertTrue(PromptCacheKeyMode.AUTO.resolve(true));
        assertFalse(PromptCacheKeyMode.AUTO.resolve(false));
    }

    @Test
    void parseEnabledTokens() {
        assertEquals(PromptCacheKeyMode.ENABLED, PromptCacheKeyMode.parse("true"));
        assertEquals(PromptCacheKeyMode.ENABLED, PromptCacheKeyMode.parse("1"));
        assertEquals(PromptCacheKeyMode.ENABLED, PromptCacheKeyMode.parse("yes"));
        assertEquals(PromptCacheKeyMode.ENABLED, PromptCacheKeyMode.parse("on"));
        assertEquals(PromptCacheKeyMode.ENABLED, PromptCacheKeyMode.parse("enabled"));
        // Case-insensitive and whitespace-tolerant.
        assertEquals(PromptCacheKeyMode.ENABLED, PromptCacheKeyMode.parse("  TRUE  "));
        assertEquals(PromptCacheKeyMode.ENABLED, PromptCacheKeyMode.parse("Yes"));
    }

    @Test
    void parseDisabledTokens() {
        assertEquals(PromptCacheKeyMode.DISABLED, PromptCacheKeyMode.parse("false"));
        assertEquals(PromptCacheKeyMode.DISABLED, PromptCacheKeyMode.parse("0"));
        assertEquals(PromptCacheKeyMode.DISABLED, PromptCacheKeyMode.parse("no"));
        assertEquals(PromptCacheKeyMode.DISABLED, PromptCacheKeyMode.parse("off"));
        assertEquals(PromptCacheKeyMode.DISABLED, PromptCacheKeyMode.parse("disabled"));
        assertEquals(PromptCacheKeyMode.DISABLED, PromptCacheKeyMode.parse("FALSE"));
    }

    @Test
    void parseAutoAndMalformedFallBackToAuto() {
        assertEquals(PromptCacheKeyMode.AUTO, PromptCacheKeyMode.parse("auto"));
        assertEquals(PromptCacheKeyMode.AUTO, PromptCacheKeyMode.parse(null));
        assertEquals(PromptCacheKeyMode.AUTO, PromptCacheKeyMode.parse(""));
        assertEquals(PromptCacheKeyMode.AUTO, PromptCacheKeyMode.parse("   "));
        assertEquals(PromptCacheKeyMode.AUTO, PromptCacheKeyMode.parse("garbage"));
        assertEquals(PromptCacheKeyMode.AUTO, PromptCacheKeyMode.parse("maybe"));
    }
}
