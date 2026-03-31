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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default in-memory implementation of {@link AiConversationMemory}.
 *
 * <p>Uses a {@link ConcurrentHashMap} keyed by conversation ID, with a sliding
 * window that evicts the oldest non-system messages when the limit is exceeded.</p>
 */
public class InMemoryConversationMemory implements AiConversationMemory {

    private final ConcurrentMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final int maxMessages;
    private final AiCompactionStrategy compactionStrategy;

    public InMemoryConversationMemory() {
        this(20);
    }

    public InMemoryConversationMemory(int maxMessages) {
        this(maxMessages, new SlidingWindowCompaction());
    }

    public InMemoryConversationMemory(int maxMessages, AiCompactionStrategy compactionStrategy) {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("maxMessages must be >= 2, got " + maxMessages);
        }
        this.maxMessages = maxMessages;
        this.compactionStrategy = compactionStrategy;
    }

    @Override
    public List<ChatMessage> getHistory(String conversationId) {
        var messages = store.get(conversationId);
        if (messages == null) {
            return List.of();
        }
        var lock = locks.get(conversationId);
        if (lock == null) {
            return List.of();
        }
        lock.lock();
        try {
            return List.copyOf(messages);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        var lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        var messages = store.computeIfAbsent(conversationId, k -> new ArrayList<>());
        lock.lock();
        try {
            messages.add(message);
            if (messages.size() > maxMessages) {
                var compacted = compactionStrategy.compact(messages, maxMessages);
                messages.clear();
                messages.addAll(compacted);
                // Defense-in-depth: if the strategy still exceeded the cap, drop oldest non-system
                while (messages.size() > maxMessages) {
                    int oldest = -1;
                    for (int i = 0; i < messages.size(); i++) {
                        if (!"system".equals(messages.get(i).role())) {
                            oldest = i;
                            break;
                        }
                    }
                    if (oldest < 0) {
                        break;
                    }
                    messages.remove(oldest);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
        locks.remove(conversationId);
    }

    @Override
    public int maxMessages() {
        return maxMessages;
    }
}
