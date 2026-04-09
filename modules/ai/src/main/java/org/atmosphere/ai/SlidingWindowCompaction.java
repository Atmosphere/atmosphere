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
 * Default compaction strategy: drops the oldest non-system messages until
 * the message count fits within the limit. This is the same behavior as
 * the original {@link InMemoryConversationMemory} sliding window.
 */
public class SlidingWindowCompaction implements AiCompactionStrategy {

    @Override
    public List<ChatMessage> compact(List<ChatMessage> messages, int maxMessages) {
        if (messages.size() <= maxMessages) {
            return new ArrayList<>(messages);
        }
        var result = new ArrayList<>(messages);
        while (result.size() > maxMessages) {
            int oldestNonSystem = -1;
            for (int i = 0; i < result.size(); i++) {
                if (!"system".equals(result.get(i).role())) {
                    oldestNonSystem = i;
                    break;
                }
            }
            if (oldestNonSystem < 0) {
                // All remaining messages are system — hard-cap by trimming oldest
                result.removeFirst();
                continue;
            }
            result.remove(oldestNonSystem);
        }
        return result;
    }

    @Override
    public String name() {
        return "sliding-window";
    }
}
