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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizingStrategyTest {

    @Test
    void nameIsSummarizing() {
        assertEquals("summarizing", new SummarizingStrategy().name());
    }

    @Test
    void withinLimitReturnsAll() {
        var strategy = new SummarizingStrategy();
        var messages = List.of(
                ChatMessage.user("a"),
                ChatMessage.assistant("b"));
        var result = strategy.select(messages, 10);
        assertEquals(2, result.size());
    }

    @Test
    void overLimitSummarizesOldMessages() {
        var strategy = new SummarizingStrategy(2);
        var messages = List.of(
                ChatMessage.user("msg1"),
                ChatMessage.user("msg2"),
                ChatMessage.user("msg3"),
                ChatMessage.user("msg4"),
                ChatMessage.user("msg5"));
        var result = strategy.select(messages, 3);
        // Should have summary + 2 recent
        assertTrue(result.stream().anyMatch(m ->
                m.content() != null && m.content().contains("[Conversation summary")));
    }

    @Test
    void systemMessagesPreserved() {
        var strategy = new SummarizingStrategy(2);
        var messages = List.of(
                ChatMessage.system("sys prompt"),
                ChatMessage.user("u1"),
                ChatMessage.user("u2"),
                ChatMessage.user("u3"),
                ChatMessage.user("u4"));
        var result = strategy.select(messages, 3);
        assertTrue(result.stream().anyMatch(m -> "sys prompt".equals(m.content())));
    }

    @Test
    void fewNonSystemMessagesReturnsCopy() {
        var strategy = new SummarizingStrategy(10);
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("u1"),
                ChatMessage.user("u2"));
        var result = strategy.select(messages, 2);
        // nonSystem.size()==2 <= recentWindowSize==10, returns copy
        assertEquals(3, result.size());
    }

    @Test
    void resultIsUnmodifiable() {
        var strategy = new SummarizingStrategy();
        var result = strategy.select(List.of(ChatMessage.user("hi")), 10);
        assertThrows(UnsupportedOperationException.class, () -> result.add(ChatMessage.user("x")));
    }

    @Test
    void summarizeTruncatesLongContent() {
        var strategy = new SummarizingStrategy(1);
        var messages = List.of(
                ChatMessage.user("a".repeat(200)),
                ChatMessage.user("last"));
        var result = strategy.select(messages, 1);
        // Summary should have truncated the 200-char message to 100
        var summaryMsg = result.stream()
                .filter(m -> m.content() != null && m.content().contains("..."))
                .findFirst();
        assertTrue(summaryMsg.isPresent());
    }

    @Test
    void defaultRecentWindowIsSix() {
        var strategy = new SummarizingStrategy();
        // 8 messages, limit 3 → should summarize; nonSystem(8) > recentWindow(6)
        var messages = new java.util.ArrayList<ChatMessage>();
        for (int i = 0; i < 8; i++) {
            messages.add(ChatMessage.user("msg" + i));
        }
        var result = strategy.select(messages, 3);
        assertTrue(result.stream().anyMatch(m ->
                m.content() != null && m.content().contains("[Conversation summary")));
    }
}
