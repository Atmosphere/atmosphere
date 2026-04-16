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
package org.atmosphere.ai.state;

import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStateConversationMemoryTest {

    @Test
    void adapterReadsAndWritesThroughAgentState(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        AiConversationMemory memory = new AgentStateConversationMemory(state, "assistant");

        memory.addMessage("session-1", ChatMessage.user("hi"));
        memory.addMessage("session-1", ChatMessage.assistant("hello"));

        var history = memory.getHistory("session-1");
        assertEquals(2, history.size());
        assertEquals("hi", history.get(0).content());
        assertEquals("hello", history.get(1).content());

        // Underlying state has the same two messages
        assertEquals(2, state.getConversation("assistant", "session-1").size());
    }

    @Test
    void slidingWindowOnRead(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        AiConversationMemory memory = new AgentStateConversationMemory(state, "assistant", 3);

        for (var i = 0; i < 10; i++) {
            memory.addMessage("sess", ChatMessage.user("msg-" + i));
        }

        var history = memory.getHistory("sess");
        assertEquals(3, history.size());
        assertEquals("msg-7", history.get(0).content());
        assertEquals("msg-8", history.get(1).content());
        assertEquals("msg-9", history.get(2).content());

        // But the backing state keeps all 10
        assertEquals(10, state.getConversation("assistant", "sess").size());
    }

    @Test
    void clearRemovesConversation(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        AiConversationMemory memory = new AgentStateConversationMemory(state, "assistant");

        memory.addMessage("sess", ChatMessage.user("hi"));
        memory.clear("sess");

        assertTrue(memory.getHistory("sess").isEmpty());
        assertTrue(state.getConversation("assistant", "sess").isEmpty());
    }

    @Test
    void rejectsSubMinimumCap(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        assertThrows(IllegalArgumentException.class,
                () -> new AgentStateConversationMemory(state, "assistant", 1));
    }

    @Test
    void copyToUsesDefaultImplementation(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var memory = new AgentStateConversationMemory(state, "assistant");

        memory.addMessage("source", ChatMessage.user("hi"));
        memory.addMessage("source", ChatMessage.assistant("hello"));

        memory.copyTo("source", "target");

        var copied = memory.getHistory("target");
        assertEquals(2, copied.size());
        assertEquals("hi", copied.get(0).content());
        assertEquals("hello", copied.get(1).content());
    }
}
