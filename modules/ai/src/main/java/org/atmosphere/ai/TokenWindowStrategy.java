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
 * Memory strategy that keeps the most recent messages fitting within a
 * token budget. Uses a chars/4 approximation for token counting (accurate
 * enough for context window management without requiring a tokenizer).
 *
 * <p>The {@code maxMessages} parameter from {@link AiConversationMemory} is
 * honored as an additional cap on the number of retained non-system
 * messages (values {@code <= 0} disable it); the token budget bounds the
 * total size. Tool-call payloads count toward the estimate like text.</p>
 *
 * <p>The budget can be sized to the resolved model's actual context window via
 * {@link #forModel(String)} / {@link ModelWindowCatalog}, so a 200k-token model
 * is not needlessly compacted at the flat {@value #DEFAULT_MAX_TOKENS}-token
 * default. See {@link TokenWindowCompaction} for the production compaction path
 * that consumes this.</p>
 */
public class TokenWindowStrategy implements MemoryStrategy {

    private static final int CHARS_PER_TOKEN = 4;

    /**
     * Historical flat token budget, used by {@link #TokenWindowStrategy() the
     * no-arg constructor} and as {@link ModelWindowCatalog}'s fallback for an
     * unknown model — so behavior is never worse than before the catalog.
     */
    public static final int DEFAULT_MAX_TOKENS = 4000;

    private final int maxTokens;

    /**
     * @param maxTokens maximum estimated tokens to include
     */
    public TokenWindowStrategy(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * Default constructor using {@value #DEFAULT_MAX_TOKENS} tokens.
     */
    public TokenWindowStrategy() {
        this(DEFAULT_MAX_TOKENS);
    }

    /**
     * Fraction of a model's context window budgeted to retained history.
     * The remainder is headroom for the system prompt, the tool catalog,
     * grounded facts / RAG documents, and the completion itself — filling
     * the whole window with history guarantees an over-limit request.
     */
    static final double HISTORY_WINDOW_FRACTION = 0.75;

    /**
     * Build a model-aware strategy whose token budget is
     * {@value #HISTORY_WINDOW_FRACTION} of {@code model}'s context window
     * from {@link ModelWindowCatalog} (headroom stays reserved for the
     * system prompt, tool catalog and completion), or the documented
     * default when the model is unknown. Never throws on an unknown model.
     *
     * @param model the resolved model id (may be {@code null}/blank → default)
     * @return a strategy budgeted to a headroom-reserving share of the
     *         model's context window
     */
    public static TokenWindowStrategy forModel(String model) {
        var window = ModelWindowCatalog.contextWindow(model);
        return new TokenWindowStrategy(Math.max(1, (int) (window * HISTORY_WINDOW_FRACTION)));
    }

    /**
     * @return the maximum estimated token budget this strategy retains
     */
    public int maxTokens() {
        return maxTokens;
    }

    @Override
    public List<ChatMessage> select(List<ChatMessage> fullHistory, int maxMessages) {
        var tokenBudget = maxTokens;
        // Both constraints apply: the token budget bounds size, and a
        // positive maxMessages additionally caps the number of non-system
        // messages — the parameter used to be silently ignored, leaving a
        // direct caller with an unbounded message count.
        var messageBudget = maxMessages > 0 ? maxMessages : Integer.MAX_VALUE;

        // Always include system messages first
        var systemMessages = new ArrayList<ChatMessage>();
        for (var msg : fullHistory) {
            if ("system".equals(msg.role())) {
                systemMessages.add(msg);
                tokenBudget -= estimateTokens(msg);
            }
        }

        if (tokenBudget <= 0) {
            return List.copyOf(systemMessages);
        }

        // Walk backward through non-system messages, accumulating until budget exhausted
        var selected = new ArrayList<ChatMessage>();
        for (int i = fullHistory.size() - 1; i >= 0; i--) {
            var msg = fullHistory.get(i);
            if ("system".equals(msg.role())) {
                continue;
            }
            if (selected.size() >= messageBudget) {
                break;
            }
            var tokens = estimateTokens(msg);
            if (tokenBudget - tokens < 0) {
                break;
            }
            selected.addFirst(msg);
            tokenBudget -= tokens;
        }

        var result = new ArrayList<ChatMessage>(systemMessages.size() + selected.size());
        result.addAll(systemMessages);
        result.addAll(selected);
        return List.copyOf(result);
    }

    @Override
    public String name() {
        return "token-window";
    }

    private static int estimateTokens(ChatMessage msg) {
        var content = msg.content();
        var chars = content != null ? content.length() : 0;
        // Tool-call payloads consume context exactly like text — an
        // assistant message carrying a large arguments JSON used to score
        // as 1 token, blowing the budget the strategy exists to protect.
        for (var toolCall : msg.toolCalls()) {
            if (toolCall.name() != null) {
                chars += toolCall.name().length();
            }
            if (toolCall.argumentsJson() != null) {
                chars += toolCall.argumentsJson().length();
            }
        }
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }
}
