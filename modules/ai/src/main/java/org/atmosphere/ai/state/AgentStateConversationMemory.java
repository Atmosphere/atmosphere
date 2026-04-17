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
package org.atmosphere.ai.state;

import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.llm.ChatMessage;

import java.util.List;

/**
 * Compatibility adapter exposing {@link AgentState} through the legacy
 * {@link AiConversationMemory} surface. Existing pipeline code
 * ({@code MemoryCapturingSession}, {@code AiPipeline}, {@code AiStreamingSession})
 * continues to work unchanged while the underlying storage is the file-first
 * {@link AgentState} backend.
 *
 * <p>The legacy interface is scoped by a single {@code conversationId}
 * (typically {@code resource.uuid()}); {@link AgentState} is scoped by
 * {@code (agentId, sessionId)}. This adapter binds a fixed {@code agentId}
 * at construction and maps the legacy {@code conversationId} argument to
 * {@code sessionId}.</p>
 *
 * <p>Sliding-window cap is applied on read: the adapter returns at most
 * {@code maxMessages} most-recent messages even if the backend has more.
 * Callers should enforce write-side retention via
 * {@link AutoMemoryStrategy} or direct {@link AgentState#clearConversation}
 * calls.</p>
 */
public class AgentStateConversationMemory implements AiConversationMemory {

    /** Default max messages retained — matches the legacy sliding-window cap. */
    public static final int DEFAULT_MAX_MESSAGES = 20;

    private final AgentState state;
    private final String agentId;
    private final int maxMessages;

    public AgentStateConversationMemory(AgentState state, String agentId) {
        this(state, agentId, DEFAULT_MAX_MESSAGES);
    }

    public AgentStateConversationMemory(AgentState state, String agentId, int maxMessages) {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("maxMessages must be >= 2, got " + maxMessages);
        }
        this.state = state;
        this.agentId = agentId;
        this.maxMessages = maxMessages;
    }

    @Override
    public List<ChatMessage> getHistory(String conversationId) {
        var all = state.getConversation(agentId, conversationId);
        if (all.size() <= maxMessages) {
            return all;
        }
        return List.copyOf(all.subList(all.size() - maxMessages, all.size()));
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        state.appendConversation(agentId, conversationId, message);
    }

    @Override
    public void clear(String conversationId) {
        state.clearConversation(agentId, conversationId);
    }

    @Override
    public int maxMessages() {
        return maxMessages;
    }
}
