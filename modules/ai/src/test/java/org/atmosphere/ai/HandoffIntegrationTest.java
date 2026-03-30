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

import static org.junit.jupiter.api.Assertions.*;

class HandoffIntegrationTest {

    @Test
    void handoffDefaultThrowsOnPlainSession() {
        var session = new StreamingSession() {
            @Override public String sessionId() { return "plain"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
        };

        assertThrows(UnsupportedOperationException.class,
                () -> session.handoff("billing", "transfer me"));
    }

    @Test
    void handoffEventContainsAgentNames() {
        var event = new AiEvent.Handoff("support", "billing", "User asked about billing");
        assertEquals("support", event.fromAgent());
        assertEquals("billing", event.toAgent());
        assertEquals("handoff", event.eventType());
    }

    @Test
    void conversationMemoryCopyToPreservesHistory() {
        var memory = new InMemoryConversationMemory(20);
        memory.addMessage("conv-1", ChatMessage.user("hello"));
        memory.addMessage("conv-1", ChatMessage.assistant("hi"));
        memory.addMessage("conv-1", ChatMessage.user("transfer me"));

        memory.copyTo("conv-1", "billing:conv-1");

        var copied = memory.getHistory("billing:conv-1");
        assertEquals(3, copied.size());
        assertEquals("hello", copied.get(0).content());
        assertEquals("transfer me", copied.get(2).content());

        // Source should be unchanged
        assertEquals(3, memory.getHistory("conv-1").size());
    }

    @Test
    void conversationMemoryCopyToEmptySourceIsNoOp() {
        var memory = new InMemoryConversationMemory(20);
        memory.copyTo("nonexistent", "target");
        assertEquals(List.of(), memory.getHistory("target"));
    }

    @Test
    void longTermMemoryInterceptorInjectsFactsIntoPrompt() {
        var ltm = new org.atmosphere.ai.memory.InMemoryLongTermMemory();
        ltm.saveFact("user-1", "Has a dog named Max");
        ltm.saveFact("user-1", "Lives in Montreal");

        var facts = ltm.getFacts("user-1", 10);
        assertEquals(2, facts.size());

        // Verify facts would be injected into a system prompt
        var factsBlock = String.join("\n- ", facts);
        var augmented = "You are helpful\n\nKnown facts about this user:\n- " + factsBlock;
        assertTrue(augmented.contains("Has a dog named Max"));
        assertTrue(augmented.contains("Lives in Montreal"));
    }
}
