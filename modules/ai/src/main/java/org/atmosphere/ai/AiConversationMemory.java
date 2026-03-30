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
 * SPI for conversation memory. Stores chat history per conversation (keyed by
 * {@link org.atmosphere.cpr.AtmosphereResource#uuid()}) so that AI adapters
 * can provide multi-turn context to the LLM.
 *
 * <p>The default implementation is {@link InMemoryConversationMemory}, which
 * uses a sliding window to cap memory usage.</p>
 */
public interface AiConversationMemory {

    /**
     * Retrieve the conversation history for the given conversation ID.
     *
     * @param conversationId typically {@code resource.uuid()}
     * @return an unmodifiable list of prior messages, oldest first
     */
    List<ChatMessage> getHistory(String conversationId);

    /**
     * Append a message to the conversation history.
     *
     * @param conversationId typically {@code resource.uuid()}
     * @param message        the message to store
     */
    void addMessage(String conversationId, ChatMessage message);

    /**
     * Clear the conversation history for the given conversation ID.
     * Should be called when a client disconnects to prevent memory leaks.
     *
     * @param conversationId typically {@code resource.uuid()}
     */
    void clear(String conversationId);

    /**
     * Maximum number of messages to retain per conversation.
     *
     * @return the sliding window size
     */
    int maxMessages();

    /**
     * Deep copy conversation history from one conversation to another.
     * Used during agent handoffs to transfer context to the target agent.
     *
     * <p>The default implementation reads the source history and appends
     * each message to the target. Implementations may override for efficiency.</p>
     *
     * @param fromConversationId source conversation
     * @param toConversationId   target conversation
     */
    default void copyTo(String fromConversationId, String toConversationId) {
        for (var message : getHistory(fromConversationId)) {
            addMessage(toConversationId, message);
        }
    }
}
