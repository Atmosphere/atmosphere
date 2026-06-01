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
package org.atmosphere.interactions;

import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal in-memory {@link AiConversationMemory} test double keyed by conversation id. */
final class RecordingMemory implements AiConversationMemory {

    private final Map<String, List<ChatMessage>> histories = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getHistory(String conversationId) {
        return List.copyOf(histories.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        histories.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public void clear(String conversationId) {
        histories.remove(conversationId);
    }

    @Override
    public int maxMessages() {
        return 100;
    }
}
