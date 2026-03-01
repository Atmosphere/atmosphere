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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default in-memory implementation of {@link AiConversationMemory}.
 *
 * <p>Uses a {@link ConcurrentHashMap} keyed by conversation ID, with a sliding
 * window that evicts the oldest non-system messages when the limit is exceeded.</p>
 */
public class InMemoryConversationMemory implements AiConversationMemory {

    private final ConcurrentMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();
    private final int maxMessages;

    public InMemoryConversationMemory() {
        this(20);
    }

    public InMemoryConversationMemory(int maxMessages) {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("maxMessages must be >= 2, got " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    @Override
    public List<ChatMessage> getHistory(String conversationId) {
        var messages = store.get(conversationId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        var messages = store.computeIfAbsent(conversationId, k ->
                Collections.synchronizedList(new ArrayList<>()));
        synchronized (messages) {
            messages.add(message);
            // Evict oldest non-system messages when over limit
            while (messages.size() > maxMessages) {
                for (int i = 0; i < messages.size(); i++) {
                    if (!"system".equals(messages.get(i).role())) {
                        messages.remove(i);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }

    @Override
    public int maxMessages() {
        return maxMessages;
    }
}
