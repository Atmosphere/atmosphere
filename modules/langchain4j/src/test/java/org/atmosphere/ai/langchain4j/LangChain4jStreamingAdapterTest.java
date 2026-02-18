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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.Broadcaster;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class LangChain4jStreamingAdapterTest {

    private Broadcaster broadcaster;
    private StreamingSession session;
    private LangChain4jStreamingAdapter adapter;

    @BeforeMethod
    public void setUp() {
        broadcaster = mock(Broadcaster.class);
        session = StreamingSessions.start("test-session", broadcaster, "resource-1");
        adapter = new LangChain4jStreamingAdapter();
    }

    @Test
    public void testName() {
        assertEquals(adapter.name(), "langchain4j");
    }

    @Test
    public void testResponseHandlerOnPartialResponse() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onPartialResponse("Hello");
        handler.onPartialResponse(" world");

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster, times(2)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(Object::toString).toList();
        assertTrue(messages.get(0).contains("\"data\":\"Hello\""));
        assertTrue(messages.get(1).contains("\"data\":\" world\""));
    }

    @Test
    public void testResponseHandlerOnCompleteWithText() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        var aiMessage = AiMessage.from("Full response");
        var response = ChatResponse.builder().aiMessage(aiMessage).build();

        handler.onCompleteResponse(response);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster).broadcast(captor.capture());

        var msg = captor.getValue().toString();
        assertTrue(msg.contains("\"type\":\"complete\""));
        assertTrue(msg.contains("\"data\":\"Full response\""));
        assertTrue(session.isClosed());
    }

    @Test
    public void testResponseHandlerOnCompleteWithoutText() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        var response = ChatResponse.builder().aiMessage(AiMessage.from("")).build();
        handler.onCompleteResponse(response);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster).broadcast(captor.capture());

        var msg = captor.getValue().toString();
        assertTrue(msg.contains("\"type\":\"complete\""));
    }

    @Test
    public void testResponseHandlerOnError() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onError(new RuntimeException("Model timeout"));

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster).broadcast(captor.capture());

        var msg = captor.getValue().toString();
        assertTrue(msg.contains("\"type\":\"error\""));
        assertTrue(msg.contains("Model timeout"));
        assertTrue(session.isClosed());
    }

    @Test
    public void testFullStreamingLifecycle() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        // Simulate a streaming response
        handler.onPartialResponse("The ");
        handler.onPartialResponse("answer ");
        handler.onPartialResponse("is ");
        handler.onPartialResponse("42");

        var aiMessage = AiMessage.from("The answer is 42");
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(aiMessage).build());

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster, times(5)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(Object::toString).toList();

        // First 4 are tokens
        for (int i = 0; i < 4; i++) {
            assertTrue(messages.get(i).contains("\"type\":\"token\""),
                    "Message " + i + " should be token");
        }
        // Last is complete with summary
        assertTrue(messages.get(4).contains("\"type\":\"complete\""));
        assertTrue(messages.get(4).contains("\"data\":\"The answer is 42\""));

        assertTrue(session.isClosed());
    }

    @Test
    public void testAdapterWithMockModel() {
        var model = mock(StreamingChatLanguageModel.class);
        var chatRequest = mock(ChatRequest.class);

        // Capture the handler passed to the model
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("Hello");
            handler.onPartialResponse(" AI");
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("Hello AI")).build());
            return null;
        }).when(model).chat(eq(chatRequest), any(StreamingChatResponseHandler.class));

        adapter.stream(model, chatRequest, session);

        var captor = ArgumentCaptor.forClass(Object.class);
        // progress + 2 tokens + complete = 4
        verify(broadcaster, times(4)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(Object::toString).toList();
        assertTrue(messages.get(0).contains("\"type\":\"progress\""),
                "First message should be progress");
        assertTrue(messages.get(1).contains("\"data\":\"Hello\""));
        assertTrue(messages.get(2).contains("\"data\":\" AI\""));
        assertTrue(messages.get(3).contains("\"type\":\"complete\""));
    }

    @Test
    public void testAdapterWithModelError() {
        var model = mock(StreamingChatLanguageModel.class);
        var chatRequest = mock(ChatRequest.class);

        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("Starting...");
            handler.onError(new RuntimeException("Rate limited"));
            return null;
        }).when(model).chat(eq(chatRequest), any(StreamingChatResponseHandler.class));

        adapter.stream(model, chatRequest, session);

        var captor = ArgumentCaptor.forClass(Object.class);
        // progress + 1 token + error = 3
        verify(broadcaster, times(3)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(Object::toString).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"error\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Rate limited")));
        assertTrue(session.isClosed());
    }

    @Test
    public void testAdapterViaGenericInterface() {
        var model = mock(StreamingChatLanguageModel.class);
        var chatRequest = mock(ChatRequest.class);

        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("Done")).build());
            return null;
        }).when(model).chat(eq(chatRequest), any(StreamingChatResponseHandler.class));

        // Use the generic interface
        var request = new LangChain4jStreamingAdapter.LangChain4jRequest(model, chatRequest);
        adapter.stream(request, session);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture());

        assertTrue(session.isClosed());
    }
}
