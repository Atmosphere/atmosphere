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
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link AiConversationMemory} interface, focusing on the
 * {@code copyTo} default method and contract verification via a minimal
 * implementation.
 */
class AiConversationMemoryTest {

    /** Minimal implementation for testing the interface contract. */
    static class SimpleMemory implements AiConversationMemory {
        final ConcurrentHashMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();
        private final int max;

        SimpleMemory(int max) {
            this.max = max;
        }

        @Override
        public List<ChatMessage> getHistory(String conversationId) {
            var msgs = store.get(conversationId);
            return msgs != null ? List.copyOf(msgs) : List.of();
        }

        @Override
        public void addMessage(String conversationId, ChatMessage message) {
            store.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(message);
        }

        @Override
        public void clear(String conversationId) {
            store.remove(conversationId);
        }

        @Override
        public int maxMessages() {
            return max;
        }
    }

    @Test
    void copyToCopiesAllMessages() {
        var memory = new SimpleMemory(100);
        memory.addMessage("src", ChatMessage.user("hello"));
        memory.addMessage("src", ChatMessage.assistant("hi"));
        memory.addMessage("src", ChatMessage.user("how are you?"));

        memory.copyTo("src", "dest");

        var destHistory = memory.getHistory("dest");
        assertEquals(3, destHistory.size());
        assertEquals("hello", destHistory.get(0).content());
        assertEquals("hi", destHistory.get(1).content());
        assertEquals("how are you?", destHistory.get(2).content());
    }

    @Test
    void copyToPreservesRoles() {
        var memory = new SimpleMemory(100);
        memory.addMessage("src", ChatMessage.system("be helpful"));
        memory.addMessage("src", ChatMessage.user("hello"));
        memory.addMessage("src", ChatMessage.assistant("hi"));

        memory.copyTo("src", "dest");

        var destHistory = memory.getHistory("dest");
        assertEquals("system", destHistory.get(0).role());
        assertEquals("user", destHistory.get(1).role());
        assertEquals("assistant", destHistory.get(2).role());
    }

    @Test
    void copyToDoesNotAffectSource() {
        var memory = new SimpleMemory(100);
        memory.addMessage("src", ChatMessage.user("msg"));

        memory.copyTo("src", "dest");
        memory.addMessage("dest", ChatMessage.assistant("new"));

        assertEquals(1, memory.getHistory("src").size());
        assertEquals(2, memory.getHistory("dest").size());
    }

    @Test
    void copyToFromEmptyConversationResultsInEmptyTarget() {
        var memory = new SimpleMemory(100);

        memory.copyTo("empty", "dest");

        assertTrue(memory.getHistory("dest").isEmpty());
    }

    @Test
    void copyToAppendsToExistingTarget() {
        var memory = new SimpleMemory(100);
        memory.addMessage("src", ChatMessage.user("from-src"));
        memory.addMessage("dest", ChatMessage.user("existing"));

        memory.copyTo("src", "dest");

        var destHistory = memory.getHistory("dest");
        assertEquals(2, destHistory.size());
        assertEquals("existing", destHistory.get(0).content());
        assertEquals("from-src", destHistory.get(1).content());
    }

    @Test
    void clearRemovesAllMessages() {
        var memory = new SimpleMemory(100);
        memory.addMessage("conv", ChatMessage.user("hello"));
        memory.addMessage("conv", ChatMessage.assistant("hi"));

        memory.clear("conv");

        assertTrue(memory.getHistory("conv").isEmpty());
    }

    @Test
    void clearDoesNotAffectOtherConversations() {
        var memory = new SimpleMemory(100);
        memory.addMessage("conv-1", ChatMessage.user("msg1"));
        memory.addMessage("conv-2", ChatMessage.user("msg2"));

        memory.clear("conv-1");

        assertTrue(memory.getHistory("conv-1").isEmpty());
        assertEquals(1, memory.getHistory("conv-2").size());
    }

    @Test
    void getHistoryReturnsEmptyListForUnknownConversation() {
        var memory = new SimpleMemory(100);
        assertTrue(memory.getHistory("nonexistent").isEmpty());
    }

    @Test
    void maxMessagesReturnsConfiguredValue() {
        var memory = new SimpleMemory(50);
        assertEquals(50, memory.maxMessages());
    }

    @Test
    void copyToSameIdDuplicatesMessages() {
        var memory = new SimpleMemory(100);
        memory.addMessage("conv", ChatMessage.user("hello"));

        memory.copyTo("conv", "conv");

        var history = memory.getHistory("conv");
        assertEquals(2, history.size());
        assertEquals("hello", history.get(0).content());
        assertEquals("hello", history.get(1).content());
    }
}
