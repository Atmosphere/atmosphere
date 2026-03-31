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
 * SPI for compacting conversation history. Unlike {@link MemoryStrategy} which
 * selects messages for the next request (read path), compaction strategies
 * permanently reduce the stored history by summarizing or merging old messages
 * (write path).
 *
 * <p>Implementations should preserve system messages and maintain chronological order.</p>
 *
 * @see AiConversationMemory
 */
public interface AiCompactionStrategy {

    /**
     * Compact the given message list to fit within the specified maximum count.
     * System messages should be preserved. The returned list must maintain
     * chronological order and include the most recent messages verbatim.
     *
     * @param messages    the full message history
     * @param maxMessages the maximum number of messages to retain
     * @return a new list of compacted messages fitting within the limit
     */
    List<ChatMessage> compact(List<ChatMessage> messages, int maxMessages);

    /**
     * Human-readable name for this strategy.
     */
    String name();
}
