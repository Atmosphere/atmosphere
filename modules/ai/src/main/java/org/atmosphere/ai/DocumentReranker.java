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

import java.util.List;

/**
 * Second-stage reranker applied by the framework retrieval path after a
 * {@link ContextProvider} returns its candidate documents. Where
 * {@link ContextProvider#rerank(String, List)} is a per-provider override
 * hook, a {@code DocumentReranker} is endpoint-scoped: when one is configured
 * (see {@link RagRetrieval}), retrieval over-fetches {@code k * overfetch}
 * candidates and this reranker scores them back down to the top {@code k} —
 * on the single-retriever path and, per ranked list, before Reciprocal Rank
 * Fusion ({@link RrfFusion}) on the multi-retriever path.
 *
 * <p><b>Contract:</b> return at most {@code topK} documents, best first, drawn
 * from {@code documents}. Implementations MUST fail open — on any error,
 * timeout, or unusable model output, return the input in its original
 * (retriever-relevance) order trimmed to {@code topK}. Retrieval must never
 * break because reranking hiccuped; {@link RagRetrieval#rerank} additionally
 * guards against implementations that throw.</p>
 *
 * @see LlmReranker the shipped LLM-scoring implementation
 */
@FunctionalInterface
public interface DocumentReranker {

    /**
     * Rerank retrieved candidates down to the top {@code topK}.
     *
     * @param query     the (normalized) retrieval query
     * @param documents candidate documents in retriever-relevance order
     * @param topK      maximum number of documents to return
     * @return at most {@code topK} documents, most relevant first; the original
     *         order (trimmed to {@code topK}) when reranking is not possible
     */
    List<ContextProvider.Document> rerank(String query, List<ContextProvider.Document> documents, int topK);
}
