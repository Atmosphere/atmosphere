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
 * Strategy for selecting which conversation history messages to include in
 * an {@link AiRequest}. Different strategies balance context quality against
 * token budget.
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>{@link MessageWindowStrategy} — last N messages (default, simple)</li>
 *   <li>{@link TokenWindowStrategy} — last N estimated tokens</li>
 *   <li>{@link SummarizingStrategy} — periodically condenses old messages</li>
 * </ul>
 *
 * @see AiConversationMemory
 */
public interface MemoryStrategy {

    /**
     * Select messages from the full conversation history to include in the
     * next LLM request. Implementations may truncate, summarize, or
     * otherwise transform the history.
     *
     * @param fullHistory the complete conversation history
     * @param maxMessages the configured maximum message count
     * @return the selected subset of messages to include
     */
    List<ChatMessage> select(List<ChatMessage> fullHistory, int maxMessages);

    /**
     * Human-readable name for this strategy (e.g., "message-window", "token-window").
     */
    String name();
}
