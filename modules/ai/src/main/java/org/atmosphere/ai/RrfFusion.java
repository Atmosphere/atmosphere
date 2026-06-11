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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reciprocal Rank Fusion (RRF) over several ranked retriever result lists. When
 * an endpoint wires more than one {@link ContextProvider} (e.g. a vector store
 * plus a long-term memory recall), each returns its own ranked list; RRF merges
 * them into one consistently-ranked, de-duplicated list so a document the
 * retrievers agree on outranks one that only a single retriever surfaced.
 *
 * <p>Score for a document {@code d} is {@code sum over lists of 1 / (k + rank(d))}
 * with a 1-based rank; documents absent from a list contribute nothing. Higher
 * total wins. Duplicates are collapsed by {@code source} (falling back to a
 * content prefix), keeping the first occurrence.</p>
 */
public final class RrfFusion {

    /** The standard RRF smoothing constant from the literature. */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * Fuse several ranked document lists into one.
     *
     * @param rankedLists per-retriever ranked lists (each already in best-first
     *                    order); {@code null} entries are ignored
     * @param k           RRF smoothing constant ({@code <= 0} uses {@link #DEFAULT_K})
     * @param topN        cap on the fused result ({@code <= 0} returns all)
     */
    public static List<ContextProvider.Document> fuse(
            List<List<ContextProvider.Document>> rankedLists, int k, int topN) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return List.of();
        }
        var effectiveK = k > 0 ? k : DEFAULT_K;
        var scores = new LinkedHashMap<String, Double>();
        var byKey = new LinkedHashMap<String, ContextProvider.Document>();
        for (var list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (var rank = 0; rank < list.size(); rank++) {
                var doc = list.get(rank);
                if (doc == null) {
                    continue;
                }
                var key = keyOf(doc);
                scores.merge(key, 1.0 / (effectiveK + rank + 1), Double::sum);
                byKey.putIfAbsent(key, doc);
            }
        }
        return scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((java.util.Map.Entry<String, Double> e) -> e.getValue())
                        .reversed())
                .limit(topN > 0 ? topN : Long.MAX_VALUE)
                .map(e -> byKey.get(e.getKey()))
                .toList();
    }

    private static String keyOf(ContextProvider.Document doc) {
        if (doc.source() != null && !doc.source().isBlank()) {
            return "s:" + doc.source();
        }
        var content = doc.content() == null ? "" : doc.content();
        return "c:" + (content.length() > 200 ? content.substring(0, 200) : content);
    }
}
