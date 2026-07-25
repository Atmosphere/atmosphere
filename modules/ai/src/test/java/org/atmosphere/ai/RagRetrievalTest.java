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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagRetrieval} — config resolution ({@code org.atmosphere.ai.rag.*}),
 * over-fetch sizing gated on an active reranker, and the defensive fail-open
 * around a misbehaving {@link DocumentReranker}.
 */
public class RagRetrievalTest {

    private AtmosphereConfig config(String reranker, Integer overfetch) {
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(RagRetrieval.RERANKER_KEY)).thenReturn(reranker);
        // Unset int params behave like a real config: hand back the caller's default.
        when(cfg.getInitParameter(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        if (overfetch != null) {
            when(cfg.getInitParameter(RagRetrieval.OVERFETCH_KEY, RagRetrieval.DEFAULT_OVERFETCH))
                    .thenReturn(overfetch);
        }
        return cfg;
    }

    private static List<ContextProvider.Document> docs(int count) {
        var result = new ArrayList<ContextProvider.Document>(count);
        for (var i = 0; i < count; i++) {
            result.add(new ContextProvider.Document("content-" + i, "doc-" + i + ".md", 1.0));
        }
        return List.copyOf(result);
    }

    @Test
    public void disabledIsByteIdenticalLegacyBehavior() {
        var disabled = RagRetrieval.disabled();
        assertFalse(disabled.rerankerActive());
        assertEquals(5, disabled.fetchSize(5));

        // No trimming, no copying — the exact input list flows through, even
        // when a misbehaving provider returned more than top-k.
        var seven = docs(7);
        assertSame(seven, disabled.rerank("query", seven, 5));
    }

    @Test
    public void nullConfigResolvesDisabled() {
        assertFalse(RagRetrieval.resolve(null, null).rerankerActive());
    }

    @Test
    public void unsetResolvesDisabled() {
        assertFalse(RagRetrieval.resolve(config(null, null), null).rerankerActive());
    }

    @Test
    public void noneResolvesDisabled() {
        assertFalse(RagRetrieval.resolve(config("none", null), null).rerankerActive());
    }

    @Test
    public void unknownValueResolvesDisabled() {
        assertFalse(RagRetrieval.resolve(config("cross-encoder", null), null).rerankerActive());
    }

    @Test
    public void llmResolvesActiveRerankerWithDefaultOverfetch() {
        var retrieval = RagRetrieval.resolve(config("llm", null), null);
        assertTrue(retrieval.rerankerActive());
        assertEquals(RagRetrieval.DEFAULT_OVERFETCH, retrieval.overfetch());
        assertEquals(5 * RagRetrieval.DEFAULT_OVERFETCH, retrieval.fetchSize(5));
    }

    @Test
    public void llmIsCaseInsensitive() {
        assertTrue(RagRetrieval.resolve(config("LLM", null), null).rerankerActive());
    }

    @Test
    public void overfetchIsClampedToBounds() {
        assertEquals(RagRetrieval.MAX_OVERFETCH,
                RagRetrieval.resolve(config("llm", 50), null).overfetch());
        assertEquals(1, RagRetrieval.resolve(config("llm", 0), null).overfetch());
        assertEquals(1, RagRetrieval.resolve(config("llm", -3), null).overfetch());
    }

    @Test
    public void ofNullRerankerIsDisabled() {
        assertFalse(RagRetrieval.of(null, 3).rerankerActive());
    }

    @Test
    public void activeRerankAppliesRerankerAndTrimsToTopK() {
        DocumentReranker reversing = (query, documents, topK) -> {
            var reversed = new ArrayList<>(documents);
            Collections.reverse(reversed);
            return reversed;    // deliberately over-long: RagRetrieval must trim
        };
        var retrieval = RagRetrieval.of(reversing, 3);

        var out = retrieval.rerank("query", docs(4), 2);

        assertEquals(2, out.size());
        assertEquals("doc-3.md", out.get(0).source());
        assertEquals("doc-2.md", out.get(1).source());
    }

    @Test
    public void throwingRerankerFailsOpenToRetrieverOrder() {
        var retrieval = RagRetrieval.of((query, documents, topK) -> {
            throw new IllegalStateException("scorer exploded");
        }, 3);

        var out = retrieval.rerank("query", docs(4), 2);

        assertEquals("doc-0.md", out.get(0).source());
        assertEquals("doc-1.md", out.get(1).source());
        assertEquals(2, out.size());
    }

    @Test
    public void nullReturningRerankerFailsOpenToRetrieverOrder() {
        var retrieval = RagRetrieval.of((query, documents, topK) -> null, 3);

        var out = retrieval.rerank("query", docs(4), 2);

        assertEquals("doc-0.md", out.get(0).source());
        assertEquals("doc-1.md", out.get(1).source());
        assertEquals(2, out.size());
    }

    @Test
    public void singleCandidateSkipsReranker() {
        var invoked = new boolean[1];
        var retrieval = RagRetrieval.of((query, documents, topK) -> {
            invoked[0] = true;
            return documents;
        }, 3);

        var single = docs(1);
        assertSame(single, retrieval.rerank("query", single, 5));
        assertFalse(invoked[0], "one candidate has nothing to reorder");
    }

    @Test
    public void fetchSizeLeavesNonPositiveTopKAlone() {
        var retrieval = RagRetrieval.of((query, documents, topK) -> documents, 3);
        assertEquals(0, retrieval.fetchSize(0));
        assertEquals(-1, retrieval.fetchSize(-1));
    }
}
