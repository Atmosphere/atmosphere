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
 * reinterpreted as the maximum token count (not message count). This allows
 * fine-grained control over context window usage.</p>
 */
public class TokenWindowStrategy implements MemoryStrategy {

    private static final int CHARS_PER_TOKEN = 4;

    private final int maxTokens;

    /**
     * @param maxTokens maximum estimated tokens to include
     */
    public TokenWindowStrategy(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * Default constructor using 4000 tokens.
     */
    public TokenWindowStrategy() {
        this(4000);
    }

    @Override
    public List<ChatMessage> select(List<ChatMessage> fullHistory, int maxMessages) {
        var tokenBudget = maxTokens;

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
        return content != null ? Math.max(1, content.length() / CHARS_PER_TOKEN) : 1;
    }
}
