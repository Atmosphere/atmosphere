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
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Semantic (embedding-similarity) {@link ResponseCache}. Where the default
 * exact cache only hits on a byte-identical prompt, this returns a cached
 * response for a <em>near-duplicate</em> prompt — "what's the weather in Paris?"
 * vs "tell me the Paris weather" — by comparing the prompt's embedding against
 * stored ones and serving the best match above a cosine-similarity threshold.
 *
 * <p>Unlike the exact cache, the {@code key} argument is the <strong>raw prompt
 * text</strong> (not the {@link CacheKey} hash) — {@link AiPipeline} threads the
 * message through when the installed cache is a {@code SemanticResponseCache}.</p>
 *
 * <p>Fail-safe: with no {@link EmbeddingRuntime} available (or an embedding
 * error) the cache degrades to a pure miss / no-op rather than failing the turn.
 * Bounded by {@code maxEntries} (Backpressure, Invariant #3); thread-safe.</p>
 */
public final class SemanticResponseCache implements ResponseCache {

    private static final Logger logger = LoggerFactory.getLogger(SemanticResponseCache.class);

    /** Conservative default — only serve a cached answer for a very close prompt. */
    public static final double DEFAULT_THRESHOLD = 0.92;
    private static final int DEFAULT_MAX_ENTRIES = 1000;

    private record Entry(float[] embedding, CachedResponse response) { }

    private final EmbeddingRuntime embeddings;
    private final double threshold;
    private final int maxEntries;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final Object lock = new Object();

    /** Resolve the embedding runtime from the classpath; default threshold/size. */
    public SemanticResponseCache() {
        this(EmbeddingRuntimeResolver.resolve().orElse(null), DEFAULT_THRESHOLD, DEFAULT_MAX_ENTRIES);
    }

    public SemanticResponseCache(EmbeddingRuntime embeddings) {
        this(embeddings, DEFAULT_THRESHOLD, DEFAULT_MAX_ENTRIES);
    }

    public SemanticResponseCache(EmbeddingRuntime embeddings, double threshold, int maxEntries) {
        this.embeddings = embeddings;
        this.threshold = threshold;
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        if (embeddings == null) {
            logger.warn("SemanticResponseCache has no EmbeddingRuntime — it will never hit. "
                    + "Install an embedding backend or use the exact InMemoryResponseCache.");
        }
    }

    /** The cosine-similarity threshold above which a stored response is served. */
    public double threshold() {
        return threshold;
    }

    @Override
    public Optional<CachedResponse> get(String promptText) {
        if (embeddings == null || promptText == null || promptText.isBlank()) {
            return Optional.empty();
        }
        float[] query;
        try {
            query = embeddings.embed(promptText);
        } catch (RuntimeException e) {
            logger.debug("Embedding failed for semantic cache lookup: {}", e.toString());
            return Optional.empty();
        }
        if (query == null || query.length == 0) {
            return Optional.empty();
        }
        var now = Instant.now();
        synchronized (lock) {
            entries.removeIf(e -> e.response().isExpired(now));
            Entry best = null;
            var bestScore = threshold;
            for (var entry : entries) {
                var score = cosine(query, entry.embedding());
                if (score >= bestScore) {
                    bestScore = score;
                    best = entry;
                }
            }
            if (best != null) {
                logger.debug("Semantic cache HIT (similarity {})", bestScore);
                return Optional.of(best.response());
            }
        }
        return Optional.empty();
    }

    @Override
    public void put(String promptText, CachedResponse response) {
        if (embeddings == null || promptText == null || promptText.isBlank() || response == null) {
            return;
        }
        float[] embedding;
        try {
            embedding = embeddings.embed(promptText);
        } catch (RuntimeException e) {
            logger.debug("Embedding failed for semantic cache store: {}", e.toString());
            return;
        }
        if (embedding == null || embedding.length == 0) {
            return;
        }
        synchronized (lock) {
            entries.addLast(new Entry(embedding, response));
            while (entries.size() > maxEntries) {
                entries.removeFirst();
            }
        }
    }

    @Override
    public void invalidate(String promptText) {
        // Best-effort: drop any entry whose stored prompt embeds identically.
        if (embeddings == null || promptText == null) {
            return;
        }
        float[] target;
        try {
            target = embeddings.embed(promptText);
        } catch (RuntimeException e) {
            return;
        }
        synchronized (lock) {
            entries.removeIf(e -> cosine(target, e.embedding()) >= 0.9999);
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    @Override
    public void close() {
        clear();
    }

    /** Cosine similarity of two equal-length vectors; 0 on length mismatch. */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (var i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
