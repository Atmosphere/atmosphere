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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MemoryCapturingSession}.
 */
public class MemoryCapturingSessionTest {

    private StreamingSession delegate;
    private InMemoryConversationMemory memory;

    @BeforeEach
    public void setUp() {
        delegate = mock(StreamingSession.class);
        memory = new InMemoryConversationMemory();
    }

    @Test
    public void testSendAccumulatesTokensAndDelegates() {
        var session = new MemoryCapturingSession(delegate, memory, "conv-1", "Hello");

        session.send("Hi");
        session.send(" there");
        session.send("!");

        verify(delegate).send("Hi");
        verify(delegate).send(" there");
        verify(delegate).send("!");
    }

    @Test
    public void testCompleteSavesUserAndAssistantMessages() {
        var session = new MemoryCapturingSession(delegate, memory, "conv-1", "Hello");

        session.send("Hi");
        session.send(" there!");
        session.complete();

        var history = memory.getHistory("conv-1");
        assertEquals(2, history.size());
        assertEquals(ChatMessage.user("Hello"), history.get(0));
        assertEquals(ChatMessage.assistant("Hi there!"), history.get(1));
        verify(delegate).complete();
    }

    @Test
    public void testCompleteWithSummaryUsesSummary() {
        var session = new MemoryCapturingSession(delegate, memory, "conv-1", "Hello");

        session.send("Hi");
        session.send(" there!");
        session.complete("Summary response");

        var history = memory.getHistory("conv-1");
        assertEquals(2, history.size());
        assertEquals(ChatMessage.user("Hello"), history.get(0));
        assertEquals(ChatMessage.assistant("Summary response"), history.get(1));
        verify(delegate).complete("Summary response");
    }

    @Test
    public void testErrorSavesOnlyUserMessage() {
        var session = new MemoryCapturingSession(delegate, memory, "conv-1", "Hello");

        session.send("partial");
        var error = new RuntimeException("LLM failure");
        session.error(error);

        var history = memory.getHistory("conv-1");
        assertEquals(1, history.size());
        assertEquals(ChatMessage.user("Hello"), history.get(0));
        verify(delegate).error(error);
    }

    @Test
    public void testCompleteWithEmptyResponseSavesOnlyUserMessage() {
        var session = new MemoryCapturingSession(delegate, memory, "conv-1", "Hello");
        // No tokens sent, then complete
        session.complete();

        var history = memory.getHistory("conv-1");
        assertEquals(1, history.size());
        assertEquals(ChatMessage.user("Hello"), history.get(0));
    }

    @Test
    public void testDelegateMethodsPassThrough() {
        var session = new MemoryCapturingSession(delegate, memory, "conv-1", "Hello");

        when(delegate.sessionId()).thenReturn("session-42");
        when(delegate.isClosed()).thenReturn(false);

        assertEquals("session-42", session.sessionId());
        assertFalse(session.isClosed());

        session.sendMetadata("key", "value");
        verify(delegate).sendMetadata("key", "value");

        session.progress("thinking");
        verify(delegate).progress("thinking");
    }

    @Test
    public void testMultipleTurnsSaveCorrectly() {
        // Simulate first turn
        var session1 = new MemoryCapturingSession(delegate, memory, "conv-1", "First question");
        session1.send("First answer");
        session1.complete();

        // Simulate second turn
        var session2 = new MemoryCapturingSession(delegate, memory, "conv-1", "Second question");
        session2.send("Second answer");
        session2.complete();

        var history = memory.getHistory("conv-1");
        assertEquals(4, history.size());
        assertEquals("First question", history.get(0).content());
        assertEquals("First answer", history.get(1).content());
        assertEquals("Second question", history.get(2).content());
        assertEquals("Second answer", history.get(3).content());
    }
}
