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

import java.util.List;

/**
 * Default memory strategy: keeps the last N messages, preserving system messages.
 * This is the strategy used by {@link InMemoryConversationMemory} today,
 * extracted into the {@link MemoryStrategy} SPI.
 */
public class MessageWindowStrategy implements MemoryStrategy {

    @Override
    public List<ChatMessage> select(List<ChatMessage> fullHistory, int maxMessages) {
        if (fullHistory.size() <= maxMessages) {
            return List.copyOf(fullHistory);
        }
        // Keep system messages + the tail of the conversation
        var systemMessages = fullHistory.stream()
                .filter(m -> "system".equals(m.role()))
                .toList();
        var nonSystem = fullHistory.stream()
                .filter(m -> !"system".equals(m.role()))
                .toList();

        var budget = maxMessages - systemMessages.size();
        if (budget <= 0) {
            return List.copyOf(systemMessages);
        }

        var tail = nonSystem.subList(
                Math.max(0, nonSystem.size() - budget), nonSystem.size());

        var result = new java.util.ArrayList<ChatMessage>(systemMessages.size() + tail.size());
        result.addAll(systemMessages);
        result.addAll(tail);
        return List.copyOf(result);
    }

    @Override
    public String name() {
        return "message-window";
    }
}
