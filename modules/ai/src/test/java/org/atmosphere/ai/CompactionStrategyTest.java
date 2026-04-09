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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionStrategyTest {

    @Test
    void slidingWindowDropsOldestNonSystem() {
        var strategy = new SlidingWindowCompaction();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("msg1"),
                ChatMessage.assistant("reply1"),
                ChatMessage.user("msg2"),
                ChatMessage.assistant("reply2"),
                ChatMessage.user("msg3")
        ));

        var compacted = strategy.compact(messages, 4);

        assertEquals(4, compacted.size());
        assertEquals("system", compacted.get(0).role());
        assertEquals("sys", compacted.get(0).content());
        // Oldest non-system messages should have been removed
        assertEquals("msg3", compacted.get(compacted.size() - 1).content());
    }

    @Test
    void slidingWindowPreservesAllSystemMessages() {
        var strategy = new SlidingWindowCompaction();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys1"),
                ChatMessage.system("sys2"),
                ChatMessage.user("msg1"),
                ChatMessage.assistant("reply1")
        ));

        var compacted = strategy.compact(messages, 3);

        assertEquals(3, compacted.size());
        long systemCount = compacted.stream()
                .filter(m -> "system".equals(m.role())).count();
        assertEquals(2, systemCount);
    }

    @Test
    void slidingWindowNoOpWhenUnderLimit() {
        var strategy = new SlidingWindowCompaction();
        var messages = List.of(
                ChatMessage.user("msg1"),
                ChatMessage.assistant("reply1")
        );

        var compacted = strategy.compact(messages, 10);

        assertEquals(2, compacted.size());
    }

    @Test
    void summarizingCompactionCreatesSummary() {
        var strategy = new SummarizingCompaction(2);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("old1"),
                ChatMessage.assistant("old-reply1"),
                ChatMessage.user("old2"),
                ChatMessage.assistant("old-reply2"),
                ChatMessage.user("recent1"),
                ChatMessage.assistant("recent2")
        ));

        var compacted = strategy.compact(messages, 10);

        // Should have: system, summary, recent1, recent2
        assertEquals("system", compacted.get(0).role());
        assertTrue(compacted.get(1).content().contains("[Conversation summary of"));
        assertEquals("recent1", compacted.get(compacted.size() - 2).content());
        assertEquals("recent2", compacted.get(compacted.size() - 1).content());
    }

    @Test
    void summarizingCompactionNoOpWhenFewMessages() {
        var strategy = new SummarizingCompaction(6);
        var messages = List.of(
                ChatMessage.user("msg1"),
                ChatMessage.assistant("reply1")
        );

        var compacted = strategy.compact(messages, 10);

        assertEquals(2, compacted.size());
    }

    @Test
    void memoryUsesCustomCompactionStrategy() {
        var strategy = new SlidingWindowCompaction();
        var memory = new InMemoryConversationMemory(4, strategy);

        memory.addMessage("conv1", ChatMessage.system("sys"));
        memory.addMessage("conv1", ChatMessage.user("m1"));
        memory.addMessage("conv1", ChatMessage.assistant("r1"));
        memory.addMessage("conv1", ChatMessage.user("m2"));
        memory.addMessage("conv1", ChatMessage.assistant("r2"));

        var history = memory.getHistory("conv1");
        assertTrue(history.size() <= 4);
        assertEquals("system", history.get(0).role());
    }

    @Test
    void slidingWindowHardCapsWhenAllSystemMessages() {
        // Regression: when all messages are system role, the compaction loop
        // would break early without enforcing the cap (review Window 9 P2)
        var strategy = new SlidingWindowCompaction();
        var messages = new java.util.ArrayList<ChatMessage>();
        for (int i = 0; i < 10; i++) {
            messages.add(ChatMessage.system("system-" + i));
        }
        var compacted = strategy.compact(messages, 3);
        assertTrue(compacted.size() <= 3,
                "Hard cap must be enforced even with only system messages, got " + compacted.size());
    }

    @Test
    void memoryWithSummarizingCompaction() {
        var strategy = new SummarizingCompaction(2);
        var memory = new InMemoryConversationMemory(4, strategy);

        memory.addMessage("conv1", ChatMessage.system("sys"));
        memory.addMessage("conv1", ChatMessage.user("old1"));
        memory.addMessage("conv1", ChatMessage.assistant("old-reply1"));
        memory.addMessage("conv1", ChatMessage.user("old2"));
        memory.addMessage("conv1", ChatMessage.assistant("old-reply2"));

        var history = memory.getHistory("conv1");
        // System + summary + 2 recent messages
        assertTrue(history.stream().anyMatch(m ->
                m.content() != null && m.content().contains("[Conversation summary")));
    }

    @Test
    void compactionDoesNotReturnSameListReference() {
        // Regression: if compact() returns the same List reference as the input,
        // InMemoryConversationMemory.addMessage() does clear()+addAll() on it,
        // wiping the history to size 0.
        var sliding = new SlidingWindowCompaction();
        var messages = new ArrayList<>(List.of(ChatMessage.user("msg1")));
        var compacted = sliding.compact(messages, 10);
        assertTrue(compacted != messages, "SlidingWindowCompaction must not return same reference");

        var summarizing = new SummarizingCompaction(6);
        var compacted2 = summarizing.compact(messages, 10);
        assertTrue(compacted2 != messages, "SummarizingCompaction must not return same reference");
    }

    @Test
    void compactionClearAddAllDoesNotWipeHistory() {
        // Regression: simulate InMemoryConversationMemory's clear+addAll pattern
        var strategy = new SummarizingCompaction(2);
        var memory = new InMemoryConversationMemory(3, strategy);

        memory.addMessage("conv1", ChatMessage.user("m1"));
        memory.addMessage("conv1", ChatMessage.assistant("r1"));
        memory.addMessage("conv1", ChatMessage.user("m2"));
        // This 4th message triggers compaction
        memory.addMessage("conv1", ChatMessage.assistant("r2"));

        var history = memory.getHistory("conv1");
        assertFalse(history.isEmpty(), "History must not be wiped by compaction");
    }

    @Test
    void slidingWindowName() {
        assertEquals("sliding-window", new SlidingWindowCompaction().name());
    }

    @Test
    void summarizingCompactionName() {
        assertEquals("summarizing", new SummarizingCompaction().name());
    }

    @Test
    void summarizingCompactionRespectsMaxMessages() {
        // Regression: recentWindowSize=6 (default) with maxMessages=4
        // used to return 5+ messages, exceeding the cap
        var strategy = new SummarizingCompaction();  // recentWindowSize=6
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("m1"),
                ChatMessage.assistant("r1"),
                ChatMessage.user("m2"),
                ChatMessage.assistant("r2"),
                ChatMessage.user("m3"),
                ChatMessage.assistant("r3"),
                ChatMessage.user("m4")
        ));

        var compacted = strategy.compact(messages, 4);

        assertTrue(compacted.size() <= 4,
                "compact() must respect maxMessages, got " + compacted.size());
        assertEquals("system", compacted.get(0).role());
    }

    @Test
    void summarizingCompactionMemoryCapEnforced() {
        // Exact scenario from the bug report: maxMessages=4, history-size was 5
        var strategy = new SummarizingCompaction(2);
        var memory = new InMemoryConversationMemory(4, strategy);

        memory.addMessage("c1", ChatMessage.system("sys"));
        memory.addMessage("c1", ChatMessage.user("m1"));
        memory.addMessage("c1", ChatMessage.assistant("r1"));
        memory.addMessage("c1", ChatMessage.user("m2"));
        memory.addMessage("c1", ChatMessage.assistant("r2"));

        var history = memory.getHistory("c1");
        assertTrue(history.size() <= 4,
                "memory cap must be enforced, got " + history.size());
    }
}
