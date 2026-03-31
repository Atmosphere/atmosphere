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
    void slidingWindowName() {
        assertEquals("sliding-window", new SlidingWindowCompaction().name());
    }

    @Test
    void summarizingCompactionName() {
        assertEquals("summarizing", new SummarizingCompaction().name());
    }
}
