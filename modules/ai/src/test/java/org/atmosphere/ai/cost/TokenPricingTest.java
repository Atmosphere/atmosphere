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
package org.atmosphere.ai.cost;

import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pricing math for {@link TokenPricing#flat} and
 * {@link TokenPricing#flatWithCachedDiscount}.
 */
class TokenPricingTest {

    @Test
    void flatIgnoresCachedInputUnchanged() {
        // flat() predates the cached discount and must keep billing every
        // input token at the full rate — byte-identical behaviour.
        var pricing = TokenPricing.flat(3.0, 15.0);
        var usage = new TokenUsage(1_000_000, 1_000_000, 900_000, 2_000_000, "gpt-4o");

        assertEquals(18.0, pricing.costUsd(usage, "gpt-4o"), 0.0001,
                "flat() must bill all 1M input at $3 regardless of cached count");
    }

    @Test
    void cachedDiscountBillsCachedSubsetAtCachedRate() {
        // OpenAI-shaped report: cached is a subset of input. 1M input of
        // which 400K cached: 600K @ $3 + 400K @ $0.30 + 1M out @ $15.
        var pricing = TokenPricing.flatWithCachedDiscount(3.0, 0.30, 15.0);
        var usage = new TokenUsage(1_000_000, 1_000_000, 400_000, 2_000_000, "gpt-4o");

        assertEquals(0.6 * 3.0 + 0.4 * 0.30 + 15.0, pricing.costUsd(usage, "gpt-4o"), 0.0001);
    }

    @Test
    void cachedDiscountWithZeroCachedMatchesFlat() {
        var discounted = TokenPricing.flatWithCachedDiscount(3.0, 0.30, 15.0);
        var flat = TokenPricing.flat(3.0, 15.0);
        var usage = new TokenUsage(500_000, 200_000, 0, 700_000, "gpt-4o");

        assertEquals(flat.costUsd(usage, "gpt-4o"), discounted.costUsd(usage, "gpt-4o"), 0.0001,
                "no cache hits -> the discounted factory must price exactly like flat()");
    }

    @Test
    void cachedDiscountClampsCachedToInput() {
        // A disjoint-count provider (Anthropic-shaped cache_read) can report
        // cached > input; the uncached remainder must clamp to zero, never
        // go negative.
        var pricing = TokenPricing.flatWithCachedDiscount(3.0, 0.30, 15.0);
        var usage = new TokenUsage(100, 0, 5_000, 100, "claude-4");

        assertEquals(100 * 0.30 / 1_000_000.0, pricing.costUsd(usage, "claude-4"), 1e-9,
                "cached is clamped to input; nothing bills at a negative count");
    }

    @Test
    void cachedDiscountClampsNegativeCachedToZero() {
        var pricing = TokenPricing.flatWithCachedDiscount(3.0, 0.30, 15.0);
        var usage = new TokenUsage(100, 50, -10, 150, "gpt-4o");

        assertEquals((100 * 3.0 + 50 * 15.0) / 1_000_000.0,
                pricing.costUsd(usage, "gpt-4o"), 1e-9,
                "a negative cached count is treated as zero");
    }

    @Test
    void cachedDiscountReportsZeroForEmptyUsage() {
        var pricing = TokenPricing.flatWithCachedDiscount(3.0, 0.30, 15.0);

        assertEquals(0.0, pricing.costUsd(null, "gpt-4o"));
        assertEquals(0.0, pricing.costUsd(new TokenUsage(0, 0, 0, 0, null), "gpt-4o"));
    }

    @Test
    void cachedDiscountRejectsNegativeRates() {
        assertThrows(IllegalArgumentException.class,
                () -> TokenPricing.flatWithCachedDiscount(-1.0, 0.30, 15.0));
        assertThrows(IllegalArgumentException.class,
                () -> TokenPricing.flatWithCachedDiscount(3.0, -0.30, 15.0));
        assertThrows(IllegalArgumentException.class,
                () -> TokenPricing.flatWithCachedDiscount(3.0, 0.30, -15.0));
    }
}
