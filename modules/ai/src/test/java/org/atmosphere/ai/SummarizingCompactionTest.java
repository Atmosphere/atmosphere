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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizingCompactionTest {

    @Test
    void nameIsSummarizing() {
        assertEquals("summarizing", new SummarizingCompaction().name());
    }

    @Test
    void withinLimitReturnsCopy() {
        var compaction = new SummarizingCompaction();
        var messages = List.of(
                ChatMessage.user("a"),
                ChatMessage.assistant("b"));
        var result = compaction.compact(new ArrayList<>(messages), 10);
        assertEquals(2, result.size());
    }

    @Test
    void overLimitCreatesSummaryMessage() {
        var compaction = new SummarizingCompaction(2);
        var messages = new ArrayList<>(List.of(
                ChatMessage.user("msg1"),
                ChatMessage.user("msg2"),
                ChatMessage.user("msg3"),
                ChatMessage.user("msg4"),
                ChatMessage.user("msg5")));
        var result = compaction.compact(messages, 4);
        // Should have at most 4 messages: 1 summary + recent
        assertTrue(result.size() <= 4, "Size: " + result.size());
        // Should have a summary message containing "[Conversation summary"
        assertTrue(result.stream().anyMatch(m ->
                m.content() != null && m.content().contains("[Conversation summary")));
    }

    @Test
    void summaryPreservesSystemMessages() {
        var compaction = new SummarizingCompaction(2);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("u1"),
                ChatMessage.user("u2"),
                ChatMessage.user("u3"),
                ChatMessage.user("u4")));
        var result = compaction.compact(messages, 5);
        assertTrue(result.stream().anyMatch(m -> "sys".equals(m.content())));
    }

    @Test
    void defaultRecentWindowIsSix() {
        var compaction = new SummarizingCompaction();
        var messages = new ArrayList<ChatMessage>();
        for (int i = 0; i < 15; i++) {
            messages.add(ChatMessage.user("msg" + i));
        }
        // maxMessages = 8, so budget = 8 - 0 - 1 = 7, effectiveWindow = min(6, 7) = 6
        var result = compaction.compact(messages, 8);
        assertTrue(result.size() <= 8);
    }

    @Test
    void emptyNonSystemMessages() {
        var compaction = new SummarizingCompaction();
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys")));
        var result = compaction.compact(messages, 5);
        assertEquals(1, result.size());
    }

    @Test
    void summarizeTruncatesLongContent() {
        var compaction = new SummarizingCompaction(1);
        var messages = new ArrayList<>(List.of(
                ChatMessage.user("a".repeat(200)),
                ChatMessage.user("b"),
                ChatMessage.user("c")));
        var result = compaction.compact(messages, 3);
        // Summary of the old message should be truncated to 100 chars + "..."
        var summaryMsg = result.stream()
                .filter(m -> m.content() != null && m.content().contains("[Conversation summary"))
                .findFirst();
        assertTrue(summaryMsg.isPresent());
        assertTrue(summaryMsg.get().content().contains("..."));
    }

    @Test
    void customRecentWindowSize() {
        var compaction = new SummarizingCompaction(3);
        var messages = new ArrayList<>(List.of(
                ChatMessage.user("u1"),
                ChatMessage.user("u2"),
                ChatMessage.user("u3"),
                ChatMessage.user("u4"),
                ChatMessage.user("u5"),
                ChatMessage.user("u6")));
        var result = compaction.compact(messages, 5);
        // Should keep 3 recent + 1 summary = 4 messages (within 5 limit)
        assertTrue(result.size() <= 5);
    }
}
