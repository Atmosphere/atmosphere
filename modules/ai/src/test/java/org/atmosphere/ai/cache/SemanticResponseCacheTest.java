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
package org.atmosphere.ai.cache;

import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticResponseCacheTest {

    /** Deterministic bag-of-words embedding over a tiny vocabulary. */
    private static final List<String> VOCAB = List.of("weather", "paris", "london", "stock", "price");

    private static final EmbeddingRuntime BAG = new EmbeddingRuntime() {
        @Override public String name() {
            return "bag";
        }

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public float[] embed(String text) {
            var v = new float[VOCAB.size()];
            var words = text.toLowerCase(Locale.ROOT).split("\\W+");
            for (var w : words) {
                var i = VOCAB.indexOf(w);
                if (i >= 0) {
                    v[i] += 1f;
                }
            }
            return v;
        }
    };

    private static CachedResponse resp(String text) {
        return new CachedResponse(text, TokenUsage.of(1, 1, 2, "m"), Instant.now(), Duration.ofMinutes(5));
    }

    @Test
    void nearDuplicatePromptHits() {
        var cache = new SemanticResponseCache(BAG);
        cache.put("what is the weather in paris", resp("18C and sunny"));

        var hit = cache.get("tell me the paris weather please");
        assertTrue(hit.isPresent(), "a reworded but semantically identical prompt must hit");
        assertEquals("18C and sunny", hit.get().text());
    }

    @Test
    void dissimilarPromptMisses() {
        var cache = new SemanticResponseCache(BAG);
        cache.put("what is the weather in paris", resp("18C"));
        assertTrue(cache.get("what is the stock price").isEmpty(),
                "an unrelated prompt must not hit");
    }

    @Test
    void belowThresholdMisses() {
        var cache = new SemanticResponseCache(BAG);
        cache.put("weather paris", resp("x"));
        // {weather,paris} vs {weather,london} => cosine 0.5, below the 0.92 default.
        assertTrue(cache.get("weather london").isEmpty(),
                "a partial overlap below the threshold must not hit");
    }

    @Test
    void noEmbeddingRuntimeDegradesToMiss() {
        var cache = new SemanticResponseCache((EmbeddingRuntime) null);
        cache.put("weather paris", resp("x"));
        assertTrue(cache.get("weather paris").isEmpty(),
                "without an embedding backend the cache fails safe to a miss, not an error");
    }

    @Test
    void expiredEntriesAreNotReturned() {
        var cache = new SemanticResponseCache(BAG);
        var stale = new CachedResponse("old", TokenUsage.of(1, 1, 2, "m"),
                Instant.now().minus(Duration.ofHours(2)), Duration.ofMinutes(5));
        cache.put("weather paris", stale);
        assertTrue(cache.get("weather paris").isEmpty(), "an expired entry must not be served");
    }

    @Test
    void boundedEviction() {
        var cache = new SemanticResponseCache(BAG, 0.92, 1);
        cache.put("weather paris", resp("first"));
        cache.put("stock price", resp("second"));
        // Only the most recent entry survives the size-1 bound.
        assertTrue(cache.get("stock price").isPresent());
        assertTrue(cache.get("weather paris").isEmpty(), "oldest entry evicted at the bound");
    }

    @Test
    void cosineExtremes() {
        assertEquals(1.0, SemanticResponseCache.cosine(new float[]{1, 0}, new float[]{2, 0}), 1e-9);
        assertEquals(0.0, SemanticResponseCache.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        assertEquals(0.0, SemanticResponseCache.cosine(new float[]{1}, new float[]{1, 1}), 1e-9);
    }
}
