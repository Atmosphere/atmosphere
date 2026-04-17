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
package org.atmosphere.ai.extensibility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Index of available tools with lexical scoring. Used by
 * {@link DynamicToolSelector} to pick a bounded set of tools for each
 * request instead of injecting every registered tool into every prompt —
 * the gap identified by the Spring IO 2026 capability matrix under
 * "Advanced tool selection".
 *
 * <h2>Scoring</h2>
 *
 * The default scorer is a simple Jaccard-like token overlap between the
 * query and the concatenation of the tool's description + tags + id. This
 * is intentionally deterministic, explainable, and library-free — proper
 * embedding-based search plugs in as an alternative scorer when an
 * {@code EmbeddingRuntime} is available.
 *
 * <h2>Thread safety</h2>
 *
 * Registration is concurrent-safe. Scoring runs lock-free against a
 * snapshot of the index.
 */
public final class ToolIndex {

    private final Map<String, ToolDescriptor> descriptors = new ConcurrentHashMap<>();

    /** Register a tool descriptor, replacing any prior entry for the same id. */
    public void register(ToolDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        descriptors.put(descriptor.id(), descriptor);
    }

    /** Register many at once. */
    public void registerAll(Collection<ToolDescriptor> many) {
        for (var d : many) {
            register(d);
        }
    }

    /** Remove a tool from the index. */
    public void unregister(String id) {
        descriptors.remove(id);
    }

    /** All registered tools, no ordering guarantees. */
    public Collection<ToolDescriptor> all() {
        return List.copyOf(descriptors.values());
    }

    public int size() {
        return descriptors.size();
    }

    /**
     * Return the top-{@code limit} tools most relevant to {@code query},
     * sorted by descending score. Ties are broken by tool id (lexicographic).
     * When {@code query} is blank, returns the first {@code limit} tools
     * by id order — deterministic fallback for runtimes that do not supply
     * a query.
     */
    public List<ToolDescriptor> search(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return descriptors.values().stream()
                    .sorted(Comparator.comparing(ToolDescriptor::id))
                    .limit(limit)
                    .toList();
        }
        var queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        var scored = new ArrayList<ScoredDescriptor>(descriptors.size());
        for (var d : descriptors.values()) {
            var score = score(queryTokens, d);
            if (score > 0) {
                scored.add(new ScoredDescriptor(d, score));
            }
        }
        scored.sort(Comparator
                .comparingDouble((ScoredDescriptor s) -> -s.score())
                .thenComparing(s -> s.descriptor().id()));
        return scored.stream()
                .limit(limit)
                .map(ScoredDescriptor::descriptor)
                .toList();
    }

    /**
     * Raw score for a single descriptor. Exposed for test diagnostics; not
     * typically called by application code.
     */
    public double rawScore(String query, ToolDescriptor descriptor) {
        var tokens = tokenize(query);
        return score(tokens, descriptor);
    }

    private static double score(Set<String> queryTokens, ToolDescriptor d) {
        var haystack = new StringBuilder(d.id()).append(' ').append(d.description());
        for (var tag : d.tags()) {
            haystack.append(' ').append(tag);
        }
        var docTokens = tokenize(haystack.toString());
        if (docTokens.isEmpty()) {
            return 0.0;
        }
        var intersection = 0;
        for (var token : queryTokens) {
            if (docTokens.contains(token)) {
                intersection++;
            }
        }
        if (intersection == 0) {
            return 0.0;
        }
        var union = new HashSet<>(queryTokens);
        union.addAll(docTokens);
        return (double) intersection / union.size();
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        var tokens = new HashSet<String>();
        var lower = text.toLowerCase(Locale.ROOT);
        var buffer = new StringBuilder();
        for (var i = 0; i < lower.length(); i++) {
            var c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                buffer.append(c);
            } else if (buffer.length() > 0) {
                if (buffer.length() >= 2) {
                    tokens.add(buffer.toString());
                }
                buffer.setLength(0);
            }
        }
        if (buffer.length() >= 2) {
            tokens.add(buffer.toString());
        }
        return tokens;
    }

    private record ScoredDescriptor(ToolDescriptor descriptor, double score) {
    }
}
