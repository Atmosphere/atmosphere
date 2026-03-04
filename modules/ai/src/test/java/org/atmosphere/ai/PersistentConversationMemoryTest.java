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

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PersistentConversationMemory}.
 */
public class PersistentConversationMemoryTest {

    @Test
    public void testAddAndRetrieveMessages() {
        var memory = new PersistentConversationMemory(new InMemoryPersistence());
        memory.addMessage("conv-1", ChatMessage.user("Hello"));
        memory.addMessage("conv-1", ChatMessage.assistant("Hi!"));

        var history = memory.getHistory("conv-1");
        assertEquals(2, history.size());
        assertEquals("Hello", history.get(0).content());
        assertEquals("Hi!", history.get(1).content());
    }

    @Test
    public void testPersistenceAcrossInstances() {
        var store = new InMemoryPersistence();
        var memory1 = new PersistentConversationMemory(store);
        memory1.addMessage("conv-1", ChatMessage.user("Remember me"));
        memory1.addMessage("conv-1", ChatMessage.assistant("I will!"));

        // Create a new memory backed by the same store — simulates server restart
        var memory2 = new PersistentConversationMemory(store);
        var history = memory2.getHistory("conv-1");
        assertEquals(2, history.size());
        assertEquals("Remember me", history.get(0).content());
    }

    @Test
    public void testSlidingWindowEviction() {
        var memory = new PersistentConversationMemory(new InMemoryPersistence(), 4);
        memory.addMessage("conv-1", ChatMessage.user("msg1"));
        memory.addMessage("conv-1", ChatMessage.assistant("resp1"));
        memory.addMessage("conv-1", ChatMessage.user("msg2"));
        memory.addMessage("conv-1", ChatMessage.assistant("resp2"));
        memory.addMessage("conv-1", ChatMessage.user("msg3"));

        var history = memory.getHistory("conv-1");
        assertEquals(4, history.size());
        assertEquals("resp1", history.get(0).content());
        assertEquals("msg3", history.get(3).content());
    }

    @Test
    public void testSlidingWindowPreservesSystemMessages() {
        var memory = new PersistentConversationMemory(new InMemoryPersistence(), 4);
        memory.addMessage("conv-1", ChatMessage.system("You are helpful"));
        memory.addMessage("conv-1", ChatMessage.user("msg1"));
        memory.addMessage("conv-1", ChatMessage.assistant("resp1"));
        memory.addMessage("conv-1", ChatMessage.user("msg2"));
        memory.addMessage("conv-1", ChatMessage.assistant("resp2"));

        var history = memory.getHistory("conv-1");
        assertEquals(4, history.size());
        assertEquals("system", history.get(0).role());
    }

    @Test
    public void testClearRemovesFromStoreAndCache() {
        var store = new InMemoryPersistence();
        var memory = new PersistentConversationMemory(store);
        memory.addMessage("conv-1", ChatMessage.user("Hello"));

        memory.clear("conv-1");

        assertTrue(memory.getHistory("conv-1").isEmpty());
        assertTrue(store.load("conv-1").isEmpty());
    }

    @Test
    public void testConversationIsolation() {
        var memory = new PersistentConversationMemory(new InMemoryPersistence());
        memory.addMessage("conv-1", ChatMessage.user("One"));
        memory.addMessage("conv-2", ChatMessage.user("Two"));

        assertEquals(1, memory.getHistory("conv-1").size());
        assertEquals(1, memory.getHistory("conv-2").size());
        assertEquals("One", memory.getHistory("conv-1").get(0).content());
        assertEquals("Two", memory.getHistory("conv-2").get(0).content());
    }

    @Test
    public void testSerializeDeserializeRoundTrip() {
        var messages = java.util.List.of(
                ChatMessage.user("Hello \"world\""),
                ChatMessage.assistant("Hi!\nHow are you?"),
                ChatMessage.system("Be helpful")
        );

        var json = PersistentConversationMemory.serialize(messages);
        var deserialized = PersistentConversationMemory.deserialize(json);

        assertEquals(3, deserialized.size());
        assertEquals("Hello \"world\"", deserialized.get(0).content());
        assertEquals("Hi!\nHow are you?", deserialized.get(1).content());
        assertEquals("system", deserialized.get(2).role());
    }

    @Test
    public void testDeserializeEmpty() {
        assertTrue(PersistentConversationMemory.deserialize(null).isEmpty());
        assertTrue(PersistentConversationMemory.deserialize("").isEmpty());
        assertTrue(PersistentConversationMemory.deserialize("[]").isEmpty());
    }

    @Test
    public void testMaxMessagesMustBeAtLeastTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistentConversationMemory(new InMemoryPersistence(), 1));
    }

    /**
     * Simple in-memory ConversationPersistence for testing.
     */
    private static class InMemoryPersistence implements ConversationPersistence {
        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        @Override
        public Optional<String> load(String conversationId) {
            return Optional.ofNullable(store.get(conversationId));
        }

        @Override
        public void save(String conversationId, String data) {
            store.put(conversationId, data);
        }

        @Override
        public void remove(String conversationId) {
            store.remove(conversationId);
        }
    }
}
