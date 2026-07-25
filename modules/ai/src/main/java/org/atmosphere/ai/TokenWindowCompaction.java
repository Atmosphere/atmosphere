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

import org.atmosphere.ai.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Model-aware {@link AiCompactionStrategy}: keeps the most recent messages that
 * fit within the resolved model's context-window token budget (from
 * {@link ModelWindowCatalog}), using the chars/4 estimate of
 * {@link TokenWindowStrategy}. Selected via the
 * {@code org.atmosphere.ai.compaction=token-window} init-param (see
 * {@link CompactionConfig}), which is the production consumer wiring this into
 * {@link InMemoryConversationMemory} across the {@code @AiEndpoint},
 * {@code @Agent}, and {@code @Coordinator} paths (Correctness Invariant #7 —
 * Mode Parity).
 *
 * <p>Unlike {@link SlidingWindowCompaction} (message-count only), the eviction
 * threshold scales with the model: a 200k-token model retains far more history
 * than the historical flat {@value TokenWindowStrategy#DEFAULT_MAX_TOKENS}-token
 * budget, while an unknown model falls back to that same default so behavior is
 * never worse than before the catalog.</p>
 *
 * <p>System messages are always preserved and the caller's {@code maxMessages}
 * hard cap is still honored as defense-in-depth (Correctness Invariant #3 — a
 * huge window never lets the stored history grow past the message bound).</p>
 */
public class TokenWindowCompaction implements AiCompactionStrategy {

    private final TokenWindowStrategy strategy;

    /**
     * @param maxTokens the model-aware token budget to compact against, e.g.
     *                  {@link ModelWindowCatalog#contextWindow(String)}
     */
    public TokenWindowCompaction(int maxTokens) {
        this.strategy = new TokenWindowStrategy(maxTokens);
    }

    @Override
    public List<ChatMessage> compact(List<ChatMessage> messages, int maxMessages) {
        // Model-aware token budget first: keep system messages plus the most
        // recent history that fits the model's context window.
        var withinBudget = strategy.select(messages, maxMessages);
        if (withinBudget.size() <= maxMessages) {
            return withinBudget;
        }
        // Defense-in-depth: a very large window must not defeat the message-count
        // cap. Drop oldest non-system messages until within the hard bound.
        var result = new ArrayList<>(withinBudget);
        while (result.size() > maxMessages) {
            int oldestNonSystem = -1;
            for (int i = 0; i < result.size(); i++) {
                if (!"system".equals(result.get(i).role())) {
                    oldestNonSystem = i;
                    break;
                }
            }
            if (oldestNonSystem < 0) {
                // All remaining messages are system — hard-cap by trimming oldest.
                result.removeFirst();
                continue;
            }
            result.remove(oldestNonSystem);
        }
        return result;
    }

    @Override
    public String name() {
        return "token-window";
    }

    /**
     * @return the model-aware token budget this strategy compacts against
     */
    public int budgetTokens() {
        return strategy.maxTokens();
    }
}
