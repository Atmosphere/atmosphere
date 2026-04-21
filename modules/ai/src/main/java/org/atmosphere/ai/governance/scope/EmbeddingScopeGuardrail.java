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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.atmosphere.ai.annotation.AgentScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default (per v4 §4 Phase AS) scope-enforcement tier — compares the
 * incoming message's embedding to the embedding of the declared
 * {@link ScopeConfig#purpose()} via cosine similarity, rejects when the
 * similarity falls below {@link ScopeConfig#similarityThreshold()}.
 *
 * <p>~5–20 ms latency typical (one embedding call per request).
 * Deterministic for a given embedding model + purpose pair. The embedding
 * of the purpose is computed once per unique purpose string and cached for
 * the lifetime of the guardrail instance — an endpoint serving 1000 req/s
 * with the same {@code @AgentScope} pays one embedding round-trip at
 * startup, not per request.</p>
 *
 * <h2>Calibration</h2>
 * {@link ScopeConfig#similarityThreshold()} defaults to {@code 0.45} in
 * {@link AgentScope#similarityThreshold()}. That floor is permissive —
 * cosine similarity for semantically related sentences from OpenAI's
 * {@code text-embedding-3-small} typically lands in {@code [0.55, 0.85]}.
 * Per v4 §9 risk #1 ("too-strict defaults over-reject legitimate queries
 * like the McDonald's allergen question"), the annotation default biases
 * toward admitting borderline traffic; operators tune upward for stricter
 * scopes.
 */
public final class EmbeddingScopeGuardrail implements ScopeGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingScopeGuardrail.class);

    /** Lazily-populated per-purpose reference vector cache. */
    private final ConcurrentHashMap<String, float[]> purposeVectorCache = new ConcurrentHashMap<>();

    private final EmbeddingRuntime runtime;

    /** Default constructor — resolves an {@link EmbeddingRuntime} via ServiceLoader. */
    public EmbeddingScopeGuardrail() {
        this(EmbeddingRuntimeResolver.resolve().orElse(null));
    }

    /** Explicit-runtime constructor — for tests and bare-JVM wiring. */
    public EmbeddingScopeGuardrail(EmbeddingRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public AgentScope.Tier tier() {
        return AgentScope.Tier.EMBEDDING_SIMILARITY;
    }

    @Override
    public Decision evaluate(AiRequest request, ScopeConfig config) {
        if (config.unrestricted()) {
            return Decision.inScope(Double.NaN);
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Decision.inScope(Double.NaN);
        }
        if (runtime == null) {
            // No embedding runtime available — fail-closed on scope would
            // starve every request; fail-open with a warning and defer to
            // the rule-based tier at the caller layer. The resolver tests
            // ensure at least one runtime is present in production wiring.
            logger.warn("No EmbeddingRuntime available — EmbeddingScopeGuardrail admits all requests. "
                    + "Install a runtime module (spring-ai/langchain4j/etc.) or switch the tier to RULE_BASED.");
            return Decision.inScope(Double.NaN);
        }

        var purposeVector = purposeVectorCache.computeIfAbsent(
                config.purpose(),
                p -> safeEmbed(p, "scope purpose"));
        if (purposeVector == null) {
            return Decision.error("failed to embed purpose");
        }

        var messageVector = safeEmbed(request.message(), "request message");
        if (messageVector == null) {
            return Decision.error("failed to embed request message");
        }

        var similarity = cosineSimilarity(purposeVector, messageVector);

        // Optional: also check against forbidden topics. A high similarity
        // to ANY forbidden topic wins over the purpose match.
        for (var topic : config.forbiddenTopics()) {
            if (topic == null || topic.isBlank()) continue;
            var topicVector = purposeVectorCache.computeIfAbsent(
                    topic.toLowerCase(Locale.ROOT),
                    t -> safeEmbed(t, "forbidden topic '" + t + "'"));
            if (topicVector == null) continue;
            var topicSim = cosineSimilarity(topicVector, messageVector);
            if (topicSim > similarity) {
                return Decision.outOfScope(
                        "message is closer to forbidden topic '" + topic
                                + "' (sim=" + round(topicSim)
                                + ") than to purpose (sim=" + round(similarity) + ")",
                        similarity);
            }
        }

        if (similarity < config.similarityThreshold()) {
            return Decision.outOfScope(
                    "message similarity " + round(similarity)
                            + " below threshold " + round(config.similarityThreshold()),
                    similarity);
        }
        return Decision.inScope(similarity);
    }

    private float[] safeEmbed(String text, String label) {
        try {
            return runtime.embed(text);
        } catch (RuntimeException e) {
            logger.error("Embedding failed for {} ({}): {}", label, runtime.name(), e.getMessage());
            return null;
        }
    }

    /**
     * Cosine similarity on float vectors. Returns {@code -1} when lengths
     * differ (shouldn't happen for a stable model but we fail gracefully).
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return -1.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
