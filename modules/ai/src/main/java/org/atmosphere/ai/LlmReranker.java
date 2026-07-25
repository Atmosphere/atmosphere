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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link DocumentReranker} that scores query-document relevance with a single
 * batched completion on the resolved {@link AgentRuntime} — the same one-shot
 * completion pattern {@link LlmSummarizingCompaction} uses. The model receives
 * the query plus every candidate (numbered, content-truncated) in ONE prompt
 * and must answer with a strict JSON array of indices in ranked order, e.g.
 * {@code [2,0,3]}.
 *
 * <p>Fail-open by design: on any error — no runtime, model failure, timeout,
 * malformed or out-of-range output — the original retriever order is returned,
 * trimmed to {@code topK}, so retrieval never breaks because reranking
 * hiccuped. Failures are logged at DEBUG only. A ranking that names only a
 * subset of the candidates is honored and back-filled with the remaining
 * documents in retriever order so the reranked list is never smaller than the
 * un-reranked list would have been.</p>
 *
 * <p>Selected via {@code org.atmosphere.ai.rag.reranker=llm} (off by default) —
 * see {@link RagRetrieval}.</p>
 */
public class LlmReranker implements DocumentReranker {

    private static final Logger logger = LoggerFactory.getLogger(LlmReranker.class);

    /** Default bound on the rerank completion; fail-open on expiry. */
    public static final long DEFAULT_TIMEOUT_MS = 10_000;

    /** Per-document content cap in the rerank prompt, keeping the batch bounded. */
    private static final int MAX_DOC_CHARS = 500;

    private static final String RERANK_SYSTEM_PROMPT =
            "You are a search-result reranker. Respond with ONLY a JSON array of "
                    + "document indices — no prose, no code fences.";

    private static final String RERANK_PROMPT = """
            Rank the numbered documents below by relevance to the query, most \
            relevant first. Respond with ONLY a JSON array of the document \
            indices in ranked order, e.g. [2,0,3]. Use each index at most once; \
            omit documents that are irrelevant to the query.

            Query: %s

            Documents:
            %s""";

    private final Supplier<AgentRuntime> runtimeSupplier;
    private final long timeoutMs;

    public LlmReranker() {
        this(AgentRuntimeResolver::resolve, DEFAULT_TIMEOUT_MS);
    }

    /**
     * @param runtimeSupplier source of the runtime that scores the batch —
     *                        typically the endpoint's resolved runtime
     * @param timeoutMs       completion bound in milliseconds ({@code <= 0}
     *                        uses {@link #DEFAULT_TIMEOUT_MS})
     */
    public LlmReranker(Supplier<AgentRuntime> runtimeSupplier, long timeoutMs) {
        this.runtimeSupplier = runtimeSupplier != null
                ? runtimeSupplier : AgentRuntimeResolver::resolve;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    @Override
    public List<ContextProvider.Document> rerank(String query,
                                                 List<ContextProvider.Document> documents, int topK) {
        if (documents == null || documents.isEmpty() || topK <= 0) {
            return List.of();
        }
        if (documents.size() == 1) {
            return documents;   // nothing to reorder — skip the model round-trip
        }
        try {
            var indices = parseIndices(requestRanking(query, documents), documents.size());
            if (indices != null) {
                return apply(indices, documents, topK);
            }
            logger.debug("LLM rerank returned no usable ranking — keeping retriever order");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("LLM rerank interrupted — keeping retriever order");
        } catch (RuntimeException e) {
            logger.debug("LLM rerank failed — keeping retriever order: {}", e.toString());
        }
        return trim(documents, topK);
    }

    private String requestRanking(String query, List<ContextProvider.Document> documents)
            throws InterruptedException {
        var numbered = new StringBuilder();
        for (var i = 0; i < documents.size(); i++) {
            var doc = documents.get(i);
            var content = doc.content() == null ? "" : doc.content();
            if (content.length() > MAX_DOC_CHARS) {
                content = content.substring(0, MAX_DOC_CHARS);
            }
            numbered.append('[').append(i).append("] (")
                    .append(doc.source() == null ? "unknown" : doc.source())
                    .append(")\n").append(content).append("\n\n");
        }

        var runtime = runtimeSupplier.get();
        // Reranking has no tool list so HITL gating is a no-op here; the null
        // ApprovalStrategy keeps us on the 15-arg constructor (mirrors
        // LlmSummarizingCompaction.llmSummarize).
        var context = new AgentExecutionContext(
                RERANK_PROMPT.formatted(query, numbered),
                RERANK_SYSTEM_PROMPT,
                null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null,
                (org.atmosphere.ai.approval.ApprovalStrategy) null);

        var text = new StringBuilder();
        var failed = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        runtime.execute(context, new StreamingSession() {
            @Override public String sessionId() { return "rag-rerank"; }
            @Override public void send(String chunk) { text.append(chunk); }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { latch.countDown(); }
            @Override public void complete(String summary) {
                if (summary != null) { text.setLength(0); text.append(summary); }
                latch.countDown();
            }
            @Override public void error(Throwable t) {
                failed.set(true);
                latch.countDown();
            }
            @Override public boolean isClosed() { return latch.getCount() == 0; }
        });

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS) || failed.get()) {
            return null;
        }
        return text.toString();
    }

    /**
     * Parse the model's answer into a list of candidate indices. Tolerates a
     * fenced or prose-wrapped answer by isolating the outermost {@code [...]};
     * everything inside is strict — a non-integer, out-of-range, or duplicate
     * index rejects the whole ranking (fail-open beats a corrupted order).
     * Returns {@code null} when unusable.
     */
    private static List<Integer> parseIndices(String raw, int size) {
        if (raw == null) {
            return null;
        }
        var text = raw.trim();
        var start = text.indexOf('[');
        var end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        var body = text.substring(start + 1, end).trim();
        if (body.isEmpty()) {
            return List.of();   // model ranked nothing — back-fill from retriever order
        }
        var seen = new HashSet<Integer>();
        var indices = new ArrayList<Integer>();
        for (var token : body.split(",")) {
            final int idx;
            try {
                idx = Integer.parseInt(token.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (idx < 0 || idx >= size || !seen.add(idx)) {
                return null;
            }
            indices.add(idx);
        }
        return indices;
    }

    private static List<ContextProvider.Document> apply(List<Integer> indices,
                                                        List<ContextProvider.Document> documents, int topK) {
        var result = new ArrayList<ContextProvider.Document>(Math.min(topK, documents.size()));
        var used = new boolean[documents.size()];
        for (var idx : indices) {
            if (result.size() == topK) {
                break;
            }
            result.add(documents.get(idx));
            used[idx] = true;
        }
        // Back-fill with the un-ranked remainder in retriever order so a
        // subset answer never shrinks recall below the un-reranked top-k.
        for (var i = 0; i < documents.size() && result.size() < topK; i++) {
            if (!used[i]) {
                result.add(documents.get(i));
            }
        }
        return List.copyOf(result);
    }

    private static List<ContextProvider.Document> trim(List<ContextProvider.Document> documents, int topK) {
        return documents.size() > topK ? List.copyOf(documents.subList(0, topK)) : documents;
    }
}
