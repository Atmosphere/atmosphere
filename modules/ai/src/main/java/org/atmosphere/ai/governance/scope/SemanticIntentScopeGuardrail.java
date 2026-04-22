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
 * Semantic-intent scope classifier — extension of
 * {@link EmbeddingScopeGuardrail} with a <i>margin</i> gate between the
 * request's similarity to the purpose and its similarity to the
 * {@link ScopeConfig#forbiddenTopics()}. Matches the default tier shape
 * of Microsoft's Agent OS (embeddings + small classifier head).
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Embed the purpose and every forbidden topic (cached per unique
 *       text). Embed the incoming request.</li>
 *   <li>Compute cosine similarity between the request and the purpose
 *       (<code>sim_p</code>) and between the request and each forbidden
 *       topic (<code>sim_f_max</code> = max over all topics).</li>
 *   <li>Admit iff <code>sim_p &gt;= similarityThreshold</code> AND
 *       <code>sim_p - sim_f_max &gt;= margin</code>.</li>
 * </ol>
 *
 * <p>The margin constraint is what distinguishes this tier from plain
 * embedding similarity. A request like "what allergens are in the
 * McFlurry" can score 0.55 to "customer support for orders" (purpose)
 * and 0.58 to "medical advice" (forbidden) — plain similarity admits;
 * semantic-intent denies because the forbidden topic is a better match.</p>
 *
 * <h2>Default margin</h2>
 * {@value #DEFAULT_MARGIN}. Tuned permissively (same reasoning as the
 * default similarity threshold) — operators widen the margin for
 * stricter corpora. Margin is configured via
 * {@link org.atmosphere.ai.annotation.AgentScope#semanticIntentMargin()}
 * when the annotation declares {@code tier = SEMANTIC_INTENT}; falls back
 * to {@value #DEFAULT_MARGIN} if absent.
 */
public final class SemanticIntentScopeGuardrail implements ScopeGuardrail {

    private static final Logger logger = LoggerFactory.getLogger(SemanticIntentScopeGuardrail.class);

    /** Default required margin between purpose and best forbidden topic similarity. */
    public static final double DEFAULT_MARGIN = 0.05;

    /** Lazily-populated per-text reference vector cache. */
    private final ConcurrentHashMap<String, float[]> vectorCache = new ConcurrentHashMap<>();

    private final EmbeddingRuntime runtime;
    private final double margin;

    /** Default constructor — resolves an {@link EmbeddingRuntime} via ServiceLoader. */
    public SemanticIntentScopeGuardrail() {
        this(EmbeddingRuntimeResolver.resolve().orElse(null), DEFAULT_MARGIN);
    }

    /** Explicit-runtime constructor — for tests and bare-JVM wiring. */
    public SemanticIntentScopeGuardrail(EmbeddingRuntime runtime) {
        this(runtime, DEFAULT_MARGIN);
    }

    public SemanticIntentScopeGuardrail(EmbeddingRuntime runtime, double margin) {
        this.runtime = runtime;
        if (margin < 0.0 || margin >= 1.0) {
            throw new IllegalArgumentException(
                    "margin must be in [0, 1), got: " + margin);
        }
        this.margin = margin;
    }

    @Override
    public AgentScope.Tier tier() {
        return AgentScope.Tier.SEMANTIC_INTENT;
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
            logger.warn("No EmbeddingRuntime available — SemanticIntentScopeGuardrail admits all requests. "
                    + "Install a runtime module or switch the tier to RULE_BASED.");
            return Decision.inScope(Double.NaN);
        }

        var purposeVector = vectorCache.computeIfAbsent(
                config.purpose(),
                p -> safeEmbed(p, "scope purpose"));
        if (purposeVector == null) {
            return Decision.error("failed to embed purpose");
        }

        var messageVector = safeEmbed(request.message(), "request message");
        if (messageVector == null) {
            return Decision.error("failed to embed request message");
        }

        var purposeSim = cosineSimilarity(purposeVector, messageVector);

        // Best forbidden-topic match (track the topic for the audit reason).
        double bestForbiddenSim = -1.0;
        String bestForbiddenTopic = null;
        for (var topic : config.forbiddenTopics()) {
            if (topic == null || topic.isBlank()) continue;
            var topicVector = vectorCache.computeIfAbsent(
                    topic.toLowerCase(Locale.ROOT),
                    t -> safeEmbed(t, "forbidden topic '" + t + "'"));
            if (topicVector == null) continue;
            var topicSim = cosineSimilarity(topicVector, messageVector);
            if (topicSim > bestForbiddenSim) {
                bestForbiddenSim = topicSim;
                bestForbiddenTopic = topic;
            }
        }

        // Hard floor — below the absolute threshold, the purpose match is
        // too weak regardless of margin. Matches the embedding-tier gate.
        if (purposeSim < config.similarityThreshold()) {
            return Decision.outOfScope(
                    "message similarity " + round(purposeSim)
                            + " below threshold " + round(config.similarityThreshold()),
                    purposeSim);
        }

        // Margin gate — purpose must beat forbidden by `margin`. When no
        // forbidden topics are configured, `bestForbiddenSim` stays at
        // -1.0 and the gate admits unconditionally.
        if (bestForbiddenTopic != null && (purposeSim - bestForbiddenSim) < margin) {
            return Decision.outOfScope(
                    "semantic-intent margin violated: purpose sim " + round(purposeSim)
                            + " only beats forbidden topic '" + bestForbiddenTopic
                            + "' (sim=" + round(bestForbiddenSim) + ") by "
                            + round(purposeSim - bestForbiddenSim)
                            + ", below margin " + round(margin),
                    purposeSim);
        }

        return Decision.inScope(purposeSim);
    }

    private float[] safeEmbed(String text, String label) {
        try {
            return runtime.embed(text);
        } catch (RuntimeException e) {
            logger.error("Embedding failed for {} ({}): {}",
                    label, runtime.name(), e.getMessage());
            return null;
        }
    }

    /** Cosine similarity on float vectors; public so tests can reuse. */
    public static double cosineSimilarity(float[] a, float[] b) {
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
