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
 * Compaction strategy that summarizes old messages into a single system-role
 * summary message. The most recent messages (within the recent window) are
 * preserved verbatim.
 *
 * <p>The default summarization is local (concatenation + truncation).
 * Override {@link #summarize(List)} for LLM-powered summarization.</p>
 */
public class SummarizingCompaction implements AiCompactionStrategy {

    private final int recentWindowSize;

    public SummarizingCompaction(int recentWindowSize) {
        this.recentWindowSize = recentWindowSize;
    }

    public SummarizingCompaction() {
        this(6);
    }

    @Override
    public List<ChatMessage> compact(List<ChatMessage> messages, int maxMessages) {
        var systemMessages = new ArrayList<ChatMessage>();
        var nonSystem = new ArrayList<ChatMessage>();
        for (var msg : messages) {
            if ("system".equals(msg.role())) {
                systemMessages.add(msg);
            } else {
                nonSystem.add(msg);
            }
        }
        if (nonSystem.size() <= recentWindowSize) {
            return messages;
        }
        var splitPoint = nonSystem.size() - recentWindowSize;
        var oldMessages = nonSystem.subList(0, splitPoint);
        var recentMessages = nonSystem.subList(splitPoint, nonSystem.size());

        var summary = summarize(oldMessages);
        var result = new ArrayList<ChatMessage>(systemMessages.size() + 1 + recentMessages.size());
        result.addAll(systemMessages);
        result.add(ChatMessage.system(
                "[Conversation summary of " + oldMessages.size() + " earlier messages]\n" + summary));
        result.addAll(recentMessages);
        return result;
    }

    /**
     * Summarize old messages into a condensed text. Override for LLM-powered
     * summarization.
     *
     * @param oldMessages the messages to summarize
     * @return the summary text
     */
    protected String summarize(List<ChatMessage> oldMessages) {
        var sb = new StringBuilder();
        for (var msg : oldMessages) {
            sb.append(msg.role()).append(": ");
            var content = msg.content();
            if (content != null && content.length() > 100) {
                sb.append(content, 0, 100).append("...");
            } else {
                sb.append(content);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public String name() {
        return "summarizing";
    }
}
