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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * Config seam for the RAG over-fetch + rerank retrieval policy, mirroring
 * {@link CompactionConfig}. Before this seam existed, the streaming path
 * hardcoded a top-5 retrieval and then invoked
 * {@link ContextProvider#rerank(String, List)} on those same five documents —
 * with the no-op default there was nothing to reorder, so no real reranker
 * shipped. {@link org.atmosphere.ai.processor.AiEndpointProcessor} resolves
 * this policy once per endpoint and {@link AiStreamingSession} applies it on
 * every retrieval turn:
 *
 * <pre>
 * org.atmosphere.ai.rag.reranker            = none (default) | llm
 * org.atmosphere.ai.rag.overfetch           = &lt;int&gt;    # candidate multiplier, clamped 1..10 (default 3)
 * org.atmosphere.ai.rag.reranker.timeout-ms = &lt;long&gt;   # rerank completion bound (default 10000)
 * </pre>
 *
 * <p>When a reranker is active, each provider fetches
 * {@code k * overfetch} candidates and the reranker scores them down to
 * {@code k} — per ranked list, before {@link RrfFusion} on the multi-retriever
 * path. When no reranker is configured ({@code none}, the default), retrieval
 * behaves exactly as before: fetch {@code k}, no extra trimming, no model
 * call. Over-fetching only happens when a reranker will actually run.</p>
 */
public final class RagRetrieval {

    private static final Logger logger = LoggerFactory.getLogger(RagRetrieval.class);

    /** Init-param: reranker name. Default {@code none}. */
    public static final String RERANKER_KEY = "org.atmosphere.ai.rag.reranker";

    /** Init-param: over-fetch multiplier applied while a reranker is active. */
    public static final String OVERFETCH_KEY = "org.atmosphere.ai.rag.overfetch";

    /** Init-param: rerank completion bound in milliseconds (fail-open on expiry). */
    public static final String RERANKER_TIMEOUT_MS_KEY = "org.atmosphere.ai.rag.reranker.timeout-ms";

    /** Reranker value selecting the shipped {@link LlmReranker}. */
    public static final String RERANKER_LLM = "llm";

    /** Documents each provider contributes per turn (the historical top-5). */
    public static final int DEFAULT_TOP_K = 5;

    /** Default candidate multiplier while a reranker is active. */
    public static final int DEFAULT_OVERFETCH = 3;

    /** Upper bound on the multiplier — over-fetch stays a bounded prefetch, not a DoS vector. */
    public static final int MAX_OVERFETCH = 10;

    private static final RagRetrieval DISABLED = new RagRetrieval(null, 1);

    private final DocumentReranker reranker;
    private final int overfetch;

    private RagRetrieval(DocumentReranker reranker, int overfetch) {
        this.reranker = reranker;
        this.overfetch = clampOverfetch(overfetch);
    }

    /** The legacy policy: fetch {@code k}, rerank nothing. */
    public static RagRetrieval disabled() {
        return DISABLED;
    }

    /**
     * Programmatic policy for a custom {@link DocumentReranker}.
     *
     * @param reranker  the reranker; {@code null} yields {@link #disabled()}
     * @param overfetch candidate multiplier, clamped to {@code 1..}{@value #MAX_OVERFETCH}
     */
    public static RagRetrieval of(DocumentReranker reranker, int overfetch) {
        return reranker == null ? DISABLED : new RagRetrieval(reranker, overfetch);
    }

    /**
     * Resolve the configured policy, falling back to {@link #disabled()} when
     * the config is absent, the value is unset, or the value is unknown
     * (logged at WARN — never fail startup over a typo in a retrieval knob).
     *
     * @param cfg             the framework config (may be {@code null} in tests)
     * @param runtimeSupplier the runtime the {@link LlmReranker} scores through —
     *                        typically the endpoint's resolved runtime;
     *                        {@code null} falls back to {@link AgentRuntimeResolver#resolve()}
     * @return the policy to hand to {@link AiStreamingSession#setRagRetrieval}
     */
    public static RagRetrieval resolve(AtmosphereConfig cfg, Supplier<AgentRuntime> runtimeSupplier) {
        if (cfg == null) {
            return DISABLED;
        }
        var value = cfg.getInitParameter(RERANKER_KEY);
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())) {
            return DISABLED;
        }
        if (!RERANKER_LLM.equalsIgnoreCase(value.trim())) {
            logger.warn("Unknown {} value '{}' — reranking disabled", RERANKER_KEY, value);
            return DISABLED;
        }
        var overfetch = cfg.getInitParameter(OVERFETCH_KEY, DEFAULT_OVERFETCH);
        var timeoutMs = cfg.getInitParameter(RERANKER_TIMEOUT_MS_KEY, (int) LlmReranker.DEFAULT_TIMEOUT_MS);
        return new RagRetrieval(new LlmReranker(runtimeSupplier, timeoutMs), overfetch);
    }

    /** Whether a configured reranker will actually run on retrieval turns. */
    public boolean rerankerActive() {
        return reranker != null;
    }

    /** The effective (clamped) over-fetch multiplier. */
    public int overfetch() {
        return overfetch;
    }

    /**
     * How many candidates to request from a provider for a {@code topK}-sized
     * context slot: {@code topK * overfetch} while a reranker is active,
     * {@code topK} unchanged otherwise.
     */
    public int fetchSize(int topK) {
        if (reranker == null || topK <= 0) {
            return topK;
        }
        return topK * overfetch;
    }

    /**
     * Apply the configured reranker to one provider's candidate list. A no-op
     * pass-through when disabled (byte-identical legacy behavior, including no
     * trimming). When active, delegates to the reranker and defensively fails
     * open — original retriever order trimmed to {@code topK} — if the
     * implementation throws or returns {@code null}, so retrieval never breaks
     * because reranking hiccuped.
     *
     * @param query     the normalized retrieval query
     * @param documents the provider's filtered candidates, retriever order
     * @param topK      the context slot size to rerank down to
     * @return the documents to hand to {@link ContextProvider#postProcess}
     */
    public List<ContextProvider.Document> rerank(String query,
                                                 List<ContextProvider.Document> documents, int topK) {
        if (reranker == null || documents == null || documents.size() < 2) {
            return documents;
        }
        try {
            var reranked = reranker.rerank(query, documents, topK);
            if (reranked != null) {
                return reranked.size() > topK
                        ? List.copyOf(reranked.subList(0, topK))
                        : reranked;
            }
            logger.warn("DocumentReranker {} returned null — keeping retriever order",
                    reranker.getClass().getSimpleName());
        } catch (RuntimeException e) {
            logger.warn("DocumentReranker {} threw — keeping retriever order: {}",
                    reranker.getClass().getSimpleName(), e.toString());
        }
        return documents.size() > topK ? List.copyOf(documents.subList(0, topK)) : documents;
    }

    private static int clampOverfetch(int value) {
        return Math.max(1, Math.min(MAX_OVERFETCH, value));
    }
}
