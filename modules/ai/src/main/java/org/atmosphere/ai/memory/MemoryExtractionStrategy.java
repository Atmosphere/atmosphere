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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentRuntime;

import java.util.List;

/**
 * Strategy for extracting long-term facts from conversations.
 * Controls when and how facts are extracted.
 */
public interface MemoryExtractionStrategy {

    /**
     * Whether facts should be extracted at this point in the conversation.
     *
     * @param conversationId the conversation identifier
     * @param message        the latest user message
     * @param messageCount   total messages in the current session
     * @return true if extraction should run now
     */
    boolean shouldExtract(String conversationId, String message, int messageCount);

    /**
     * Extract factual statements from accumulated conversation text.
     * Uses an LLM to identify key facts worth remembering.
     *
     * @param conversationText the conversation history as plain text
     * @param runtime          an AgentRuntime to use for extraction
     * @return list of concise factual statements
     */
    List<String> extractFacts(String conversationText, AgentRuntime runtime);

    /**
     * Extract on session close only (default). Cost-efficient: one LLM call
     * per session instead of per message.
     */
    static MemoryExtractionStrategy onSessionClose() {
        return new OnSessionCloseStrategy();
    }

    /**
     * Extract after every message. Real-time but expensive.
     */
    static MemoryExtractionStrategy perMessage() {
        return new PerMessageStrategy();
    }

    /**
     * Extract every N messages.
     *
     * @param interval extract after this many messages
     */
    static MemoryExtractionStrategy periodic(int interval) {
        return new PeriodicStrategy(interval);
    }
}
