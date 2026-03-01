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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InMemoryConversationMemory}.
 */
public class InMemoryConversationMemoryTest {

    @Test
    public void testEmptyHistoryForNewConversation() {
        var memory = new InMemoryConversationMemory();
        var history = memory.getHistory("conv-1");
        assertTrue(history.isEmpty());
    }

    @Test
    public void testAddAndRetrieveMessages() {
        var memory = new InMemoryConversationMemory();
        memory.addMessage("conv-1", ChatMessage.user("Hello"));
        memory.addMessage("conv-1", ChatMessage.assistant("Hi there!"));

        var history = memory.getHistory("conv-1");
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("Hello", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
        assertEquals("Hi there!", history.get(1).content());
    }

    @Test
    public void testConversationIsolation() {
        var memory = new InMemoryConversationMemory();
        memory.addMessage("conv-1", ChatMessage.user("Hello from conv-1"));
        memory.addMessage("conv-2", ChatMessage.user("Hello from conv-2"));

        assertEquals(1, memory.getHistory("conv-1").size());
        assertEquals("Hello from conv-1", memory.getHistory("conv-1").get(0).content());
        assertEquals(1, memory.getHistory("conv-2").size());
        assertEquals("Hello from conv-2", memory.getHistory("conv-2").get(0).content());
    }

    @Test
    public void testClearRemovesHistory() {
        var memory = new InMemoryConversationMemory();
        memory.addMessage("conv-1", ChatMessage.user("Hello"));
        memory.addMessage("conv-1", ChatMessage.assistant("Hi"));

        memory.clear("conv-1");
        assertTrue(memory.getHistory("conv-1").isEmpty());
    }

    @Test
    public void testSlidingWindowEvictsOldest() {
        var memory = new InMemoryConversationMemory(4);
        memory.addMessage("conv-1", ChatMessage.user("msg1"));
        memory.addMessage("conv-1", ChatMessage.assistant("resp1"));
        memory.addMessage("conv-1", ChatMessage.user("msg2"));
        memory.addMessage("conv-1", ChatMessage.assistant("resp2"));

        // At limit (4), add one more — should evict the oldest non-system message
        memory.addMessage("conv-1", ChatMessage.user("msg3"));

        var history = memory.getHistory("conv-1");
        assertEquals(4, history.size());
        // The oldest message ("msg1") should have been evicted
        assertEquals("resp1", history.get(0).content());
        assertEquals("msg3", history.get(3).content());
    }

    @Test
    public void testSlidingWindowPreservesSystemMessages() {
        var memory = new InMemoryConversationMemory(4);
        memory.addMessage("conv-1", ChatMessage.system("You are helpful"));
        memory.addMessage("conv-1", ChatMessage.user("msg1"));
        memory.addMessage("conv-1", ChatMessage.assistant("resp1"));
        memory.addMessage("conv-1", ChatMessage.user("msg2"));

        // Add one more — should evict first non-system message, not the system message
        memory.addMessage("conv-1", ChatMessage.assistant("resp2"));

        var history = memory.getHistory("conv-1");
        assertEquals(4, history.size());
        // System message should still be first
        assertEquals("system", history.get(0).role());
        assertEquals("You are helpful", history.get(0).content());
    }

    @Test
    public void testMaxMessages() {
        var memory = new InMemoryConversationMemory(10);
        assertEquals(10, memory.maxMessages());
    }

    @Test
    public void testDefaultMaxMessages() {
        var memory = new InMemoryConversationMemory();
        assertEquals(20, memory.maxMessages());
    }

    @Test
    public void testMaxMessagesMustBeAtLeastTwo() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryConversationMemory(1));
    }

    @Test
    public void testGetHistoryReturnsUnmodifiableCopy() {
        var memory = new InMemoryConversationMemory();
        memory.addMessage("conv-1", ChatMessage.user("Hello"));

        var history = memory.getHistory("conv-1");
        assertThrows(UnsupportedOperationException.class,
                () -> history.add(ChatMessage.user("injected")));
    }

    @Test
    public void testClearNonExistentConversation() {
        var memory = new InMemoryConversationMemory();
        // Should not throw
        memory.clear("non-existent");
        assertTrue(memory.getHistory("non-existent").isEmpty());
    }
}
