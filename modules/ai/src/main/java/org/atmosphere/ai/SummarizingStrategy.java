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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory strategy that summarizes older messages to preserve context while
 * staying within message limits. When the history exceeds the threshold,
 * the oldest messages beyond the recent window are condensed into a single
 * system-role summary message.
 *
 * <p>The summarization is done locally by concatenating old messages into a
 * condensed format. For LLM-powered summarization, extend this class and
 * override {@link #summarize(List)}.</p>
 *
 * <p>The recent window (most recent messages) is always preserved verbatim
 * to maintain conversational continuity.</p>
 */
public class SummarizingStrategy implements MemoryStrategy {

    private static final Logger logger = LoggerFactory.getLogger(SummarizingStrategy.class);

    private final int recentWindowSize;

    /**
     * @param recentWindowSize number of most recent messages to preserve verbatim
     */
    public SummarizingStrategy(int recentWindowSize) {
        this.recentWindowSize = recentWindowSize;
    }

    /**
     * Default constructor preserving the 6 most recent messages.
     */
    public SummarizingStrategy() {
        this(6);
    }

    @Override
    public List<ChatMessage> select(List<ChatMessage> fullHistory, int maxMessages) {
        if (fullHistory.size() <= maxMessages) {
            return List.copyOf(fullHistory);
        }

        // Split: system messages + old non-system + recent non-system
        var systemMessages = new ArrayList<ChatMessage>();
        var nonSystem = new ArrayList<ChatMessage>();
        for (var msg : fullHistory) {
            if ("system".equals(msg.role())) {
                systemMessages.add(msg);
            } else {
                nonSystem.add(msg);
            }
        }

        if (nonSystem.size() <= recentWindowSize) {
            return List.copyOf(fullHistory);
        }

        // Split non-system into old (to summarize) and recent (to keep)
        var splitPoint = nonSystem.size() - recentWindowSize;
        var oldMessages = nonSystem.subList(0, splitPoint);
        var recentMessages = nonSystem.subList(splitPoint, nonSystem.size());

        var summary = summarize(oldMessages);

        var result = new ArrayList<ChatMessage>(
                systemMessages.size() + 1 + recentMessages.size());
        result.addAll(systemMessages);
        result.add(ChatMessage.system(
                "[Conversation summary of " + oldMessages.size() + " earlier messages]\n" + summary));
        result.addAll(recentMessages);
        return List.copyOf(result);
    }

    /**
     * Produce a text summary of old messages. Override for LLM-powered summarization.
     *
     * @param oldMessages the messages to summarize
     * @return a condensed text summary
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
            sb.append("\n");
        }
        logger.debug("Summarized {} old messages into {} chars",
                oldMessages.size(), sb.length());
        return sb.toString();
    }

    @Override
    public String name() {
        return "summarizing";
    }
}
