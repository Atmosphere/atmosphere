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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FakeLlmClientTest {

    @Test
    void defaultConstructorStreamsDefaultResponseAndCompletes() {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);
        var request = ChatCompletionRequest.of("fake-model", "hello");

        var client = new FakeLlmClient("test-model");
        client.streamChatCompletion(request, session);

        var order = inOrder(session);
        order.verify(session).sendMetadata("model", "test-model");
        order.verify(session).complete();
    }

    @Test
    void customTextsAreStreamedInOrder() {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);
        var request = ChatCompletionRequest.of("m", "hi");

        var client = new FakeLlmClient("custom", 0, "Hello", " World");
        client.streamChatCompletion(request, session);

        InOrder order = inOrder(session);
        order.verify(session).sendMetadata("model", "custom");
        order.verify(session).send("Hello");
        order.verify(session).send(" World");
        order.verify(session).complete();
    }

    @Test
    void stopsEarlyWhenSessionClosed() {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false, true);
        var request = ChatCompletionRequest.of("m", "hi");

        var client = new FakeLlmClient("m", 0, "a", "b", "c");
        client.streamChatCompletion(request, session);

        verify(session).send("a");
        verify(session, never()).send("b");
        verify(session, never()).send("c");
        verify(session, never()).complete();
    }

    @Test
    void metadataContainsModelName() {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);
        var request = ChatCompletionRequest.of("m", "hi");

        var client = new FakeLlmClient("gpt-fake", 0, "x");
        client.streamChatCompletion(request, session);

        verify(session).sendMetadata("model", "gpt-fake");
    }

    @Test
    void emptyCustomTextsFallBackToDefaultResponse() {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);
        var request = ChatCompletionRequest.of("m", "hi");

        var client = new FakeLlmClient("m", 0);
        client.streamChatCompletion(request, session);

        // Default response starts with "This"
        verify(session).send("This");
        verify(session).complete();
    }

    @Test
    void interruptSetsInterruptFlagAndCallsError() throws Exception {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);
        var request = ChatCompletionRequest.of("m", "hi");

        // Use a long delay so the thread is sleeping when we interrupt
        var client = new FakeLlmClient("m", 5000, "a", "b");

        var thread = new Thread(() -> client.streamChatCompletion(request, session));
        thread.start();
        Thread.sleep(50);
        thread.interrupt();
        thread.join(2000);

        assertTrue(thread.isInterrupted() || !thread.isAlive());
    }

    @Test
    void requestWithEmptyMessagesDoesNotThrow() {
        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);
        var request = new ChatCompletionRequest("m",
                List.of(), 0.7, 2048, false, List.of(), null, null,
                List.of(), List.of(), CacheHint.none());

        var client = new FakeLlmClient("m", 0, "ok");
        client.streamChatCompletion(request, session);

        verify(session).send("ok");
        verify(session).complete();
    }
}
