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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Config resolution + end-to-end pipeline behaviour for the semantic response
 * cache: a near-duplicate prompt is served from cache, a below-threshold prompt
 * dispatches to the runtime, and with the key off the pipeline behaves exactly
 * as it did before this seam existed.
 */
class ResponseCacheConfigTest {

    // ── Config resolution ──

    @Test
    void disabledByDefaultSoNothingIsInstalled() {
        var cfg = configWith(false);

        assertTrue(ResponseCacheConfig.resolve(cfg, new BagOfWordsEmbeddings()).isEmpty());

        var pipeline = pipeline(new CountingRuntime());
        assertFalse(ResponseCacheConfig.install(pipeline, ResponseCacheConfig.resolve(cfg,
                new BagOfWordsEmbeddings())));
        assertNull(pipeline.responseCache(), "no cache is installed when the key is off");
    }

    @Test
    void nullConfigResolvesToNothing() {
        assertTrue(ResponseCacheConfig.resolve(null, new BagOfWordsEmbeddings()).isEmpty());
    }

    @Test
    void enabledWithoutAnEmbeddingRuntimeInstallsNothing() {
        // Runtime truth (Invariant #5): a SemanticResponseCache with no
        // embeddings can never hit, so advertising it as installed would be a
        // lie. Nothing is installed and the pipeline stays uncached.
        var cfg = configWith(true);

        assertTrue(ResponseCacheConfig.resolve(cfg, null).isEmpty());

        var pipeline = pipeline(new CountingRuntime());
        assertFalse(ResponseCacheConfig.install(pipeline, ResponseCacheConfig.resolve(cfg, null)));
        assertNull(pipeline.responseCache());
    }

    @Test
    void enabledWithEmbeddingsResolvesABoundedSemanticCache() {
        var cfg = configWith(true);
        when(cfg.getInitParameter(ResponseCacheConfig.SEMANTIC_MAX_ENTRIES_KEY, 1_000)).thenReturn(7);
        when(cfg.getInitParameter(ResponseCacheConfig.TTL_MINUTES_KEY,
                ResponseCacheConfig.DEFAULT_TTL_MINUTES)).thenReturn(11);

        var resolved = ResponseCacheConfig.resolve(cfg, new BagOfWordsEmbeddings()).orElseThrow();

        assertInstanceOf(SemanticResponseCache.class, resolved.cache());
        assertEquals(11, resolved.ttl().toMinutes());
        assertEquals(SemanticResponseCache.DEFAULT_THRESHOLD,
                ((SemanticResponseCache) resolved.cache()).threshold());
    }

    @Test
    void thresholdIsParsedAndOutOfRangeOrGarbageFallsBackToTheDefault() {
        var withValue = configWith(true);
        when(withValue.getInitParameter(ResponseCacheConfig.SEMANTIC_THRESHOLD_KEY)).thenReturn("0.5");
        assertEquals(0.5, semanticCache(withValue).threshold());

        for (var bad : List.of("1.5", "0", "-0.3", "not-a-number")) {
            var cfg = configWith(true);
            when(cfg.getInitParameter(ResponseCacheConfig.SEMANTIC_THRESHOLD_KEY)).thenReturn(bad);
            assertEquals(SemanticResponseCache.DEFAULT_THRESHOLD, semanticCache(cfg).threshold(),
                    "a bad threshold must not silently weaken the cache: " + bad);
        }
    }

    @Test
    void nonPositiveTtlFallsBackToTheDefault() {
        var cfg = configWith(true);
        when(cfg.getInitParameter(ResponseCacheConfig.TTL_MINUTES_KEY,
                ResponseCacheConfig.DEFAULT_TTL_MINUTES)).thenReturn(0);

        var resolved = ResponseCacheConfig.resolve(cfg, new BagOfWordsEmbeddings()).orElseThrow();

        assertEquals(ResponseCacheConfig.DEFAULT_TTL_MINUTES, resolved.ttl().toMinutes());
    }

    // ── End-to-end through the pipeline gate ──

    @Test
    void nearDuplicatePromptIsServedFromTheSemanticCache() {
        var runtime = new CountingRuntime();
        var pipeline = pipeline(runtime);
        assertTrue(ResponseCacheConfig.install(pipeline,
                ResponseCacheConfig.resolve(configWith(true), new BagOfWordsEmbeddings())));

        var first = new RecordingSession();
        pipeline.execute("client", "what is the weather in paris", first);
        assertEquals(1, runtime.calls.get(), "the first prompt dispatches");
        assertEquals(Boolean.FALSE, first.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY));

        // Same words, different order/padding — byte-different, so the exact
        // cache would miss; cosine similarity over the bag of words is 1.0.
        var second = new RecordingSession();
        pipeline.execute("client", "the weather in paris what is", second);

        assertEquals(1, runtime.calls.get(),
                "a near-duplicate prompt must NOT reach the runtime");
        assertEquals(Boolean.TRUE, second.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY));
        assertEquals(first.fullText(), second.fullText());
        assertTrue(second.completed);
    }

    @Test
    void belowThresholdPromptMissesAndDispatches() {
        var runtime = new CountingRuntime();
        var pipeline = pipeline(runtime);
        ResponseCacheConfig.install(pipeline,
                ResponseCacheConfig.resolve(configWith(true), new BagOfWordsEmbeddings()));

        pipeline.execute("client", "what is the weather in paris", new RecordingSession());
        assertEquals(1, runtime.calls.get());

        var unrelated = new RecordingSession();
        pipeline.execute("client", "explain quantum tunnelling to a child", unrelated);

        assertEquals(2, runtime.calls.get(),
                "a dissimilar prompt falls below the threshold and dispatches");
        assertEquals(Boolean.FALSE, unrelated.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY));
    }

    @Test
    void exactCacheStillMissesWhatTheSemanticCacheHits() {
        // Pins WHY the semantic cache exists: the same two prompts through the
        // exact cache dispatch twice.
        var runtime = new CountingRuntime();
        var pipeline = pipeline(runtime);
        pipeline.setResponseCache(new InMemoryResponseCache(8), java.time.Duration.ofMinutes(5));
        pipeline.setDefaultCachePolicy(org.atmosphere.ai.llm.CacheHint.CachePolicy.CONSERVATIVE);

        pipeline.execute("client", "what is the weather in paris", new RecordingSession());
        pipeline.execute("client", "the weather in paris what is", new RecordingSession());

        assertEquals(2, runtime.calls.get(),
                "the exact cache keys on the request hash, so a reworded prompt misses");
    }

    @Test
    void keyOffMeansTheRuntimeIsCalledEveryTimeExactlyAsBefore() {
        var runtime = new CountingRuntime();
        var pipeline = pipeline(runtime);
        ResponseCacheConfig.install(pipeline, configWith(false));

        var first = new RecordingSession();
        pipeline.execute("client", "identical prompt", first);
        var second = new RecordingSession();
        pipeline.execute("client", "identical prompt", second);

        assertEquals(2, runtime.calls.get(), "with the key off nothing is cached");
        assertNull(pipeline.responseCache());
        assertFalse(first.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY),
                "no cache-hit frame is emitted when no cache is installed");
        assertFalse(second.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY));
    }

    // ── Fixtures ──

    private static SemanticResponseCache semanticCache(AtmosphereConfig cfg) {
        return (SemanticResponseCache) ResponseCacheConfig
                .resolve(cfg, new BagOfWordsEmbeddings()).orElseThrow().cache();
    }

    private static AtmosphereConfig configWith(boolean semanticEnabled) {
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(anyString(), anyBoolean())).thenReturn(false);
        when(cfg.getInitParameter(ResponseCacheConfig.SEMANTIC_KEY, false))
                .thenReturn(semanticEnabled);
        when(cfg.getInitParameter(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(cfg.getInitParameter(anyString())).thenReturn(null);
        return cfg;
    }

    private static AiPipeline pipeline(AgentRuntime runtime) {
        return new AiPipeline(runtime, "sys", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
    }

    /**
     * Deterministic bag-of-words embedding over a small fixed vocabulary. Real
     * arithmetic on real vectors — reordering a prompt's words yields an
     * identical vector (cosine 1.0), while a prompt with disjoint vocabulary
     * scores 0.0. No model, no network, no stub of the class under test.
     */
    private static final class BagOfWordsEmbeddings implements EmbeddingRuntime {

        private static final List<String> VOCABULARY = List.of(
                "what", "is", "the", "weather", "in", "paris",
                "explain", "quantum", "tunnelling", "to", "a", "child",
                "identical", "prompt");

        @Override
        public String name() {
            return "bag-of-words";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public float[] embed(String text) {
            var vector = new float[VOCABULARY.size()];
            for (var word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                var index = VOCABULARY.indexOf(word);
                if (index >= 0) {
                    vector[index] += 1.0f;
                }
            }
            return vector;
        }
    }

    private static final class CountingRuntime implements AgentRuntime {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return -1;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            calls.incrementAndGet();
            session.send("answer-" + calls.get() + ":" + context.message());
            session.complete();
        }
    }

    private static final class RecordingSession implements StreamingSession {

        private final List<String> sentTexts = new ArrayList<>();
        private final Map<String, Object> metadata = new HashMap<>();
        private boolean completed;

        String fullText() {
            return String.join("", sentTexts);
        }

        @Override
        public String sessionId() {
            return "test-session";
        }

        @Override
        public void send(String text) {
            sentTexts.add(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            metadata.put(key, value);
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void complete(String summary) {
            completed = true;
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return completed;
        }
    }
}
