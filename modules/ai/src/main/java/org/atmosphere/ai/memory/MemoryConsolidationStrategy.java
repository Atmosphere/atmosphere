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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentRuntime;

import java.util.List;

/**
 * Strategy for consolidating a user's long-term facts. Where
 * {@link MemoryExtractionStrategy} is the write side (pull new facts out of a
 * conversation), consolidation is the maintenance side: periodically rewrite
 * the accumulated fact set to merge duplicates, resolve contradictions
 * (keeping the most recent), and drop redundancy — so the store stays small
 * and high-signal instead of growing without bound and being trimmed by crude
 * oldest-first eviction.
 *
 * <p>It runs against the user's <em>whole</em> fact set and produces a
 * replacement set; {@link LongTermMemory#replaceFacts} applies it. Off by
 * default ({@link #disabled()}) because consolidation rewrites stored memory
 * and (for the LLM variant) costs a model call — opt in explicitly.</p>
 *
 * @since 4.0
 */
public interface MemoryConsolidationStrategy {

    /**
     * Whether consolidation should run for a user given the current number of
     * stored facts. Implementations typically gate on a size threshold so
     * consolidation is amortized rather than run on every turn.
     *
     * @param userId            the user identifier
     * @param currentFactCount  facts currently stored for the user
     * @return {@code true} to consolidate now
     */
    boolean shouldConsolidate(String userId, int currentFactCount);

    /**
     * Produce a consolidated replacement for {@code facts} (supplied
     * oldest-first). Implementations MUST NOT grow the set — the result is a
     * deduplicated/merged subset-or-equal. Returns the input unchanged when no
     * consolidation is possible or configured.
     *
     * @param facts   the user's current facts, oldest first
     * @param runtime an {@link AgentRuntime} available for LLM-backed merging
     * @return the consolidated facts
     */
    List<String> consolidate(List<String> facts, AgentRuntime runtime);

    /**
     * No-op consolidation — never triggers and never rewrites memory. The
     * default for interceptors that do not opt in.
     */
    static MemoryConsolidationStrategy disabled() {
        return DisabledConsolidationStrategy.INSTANCE;
    }

    /**
     * LLM-backed consolidation that triggers once a user has at least
     * {@code threshold} stored facts, merging them down to a default cap.
     *
     * @param threshold fact count at or above which consolidation runs (&gt; 0)
     */
    static MemoryConsolidationStrategy whenExceeding(int threshold) {
        return new LlmMemoryConsolidator(threshold);
    }

    /**
     * LLM-backed consolidation with an explicit cap on the consolidated set.
     *
     * @param threshold            fact count at or above which consolidation runs (&gt; 0)
     * @param maxConsolidatedFacts upper bound on the consolidated fact count (&gt; 0)
     */
    static MemoryConsolidationStrategy whenExceeding(int threshold, int maxConsolidatedFacts) {
        return new LlmMemoryConsolidator(threshold, maxConsolidatedFacts);
    }
}
