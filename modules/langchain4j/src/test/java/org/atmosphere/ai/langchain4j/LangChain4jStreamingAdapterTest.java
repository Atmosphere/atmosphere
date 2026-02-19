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
import org.atmosphere.cpr.AtmosphereResource;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class LangChain4jStreamingAdapterTest {

    private AtmosphereResource resource;
    private StreamingSession session;
    private LangChain4jStreamingAdapter adapter;

    @BeforeMethod
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("resource-1");
        when(resource.write(anyString())).thenReturn(resource);
        session = StreamingSessions.start("test-session", resource);
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

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(2)).write(captor.capture());

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

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

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

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

        var msg = captor.getValue().toString();
        assertTrue(msg.contains("\"type\":\"complete\""));
    }

    @Test
    public void testResponseHandlerOnError() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onError(new RuntimeException("Model timeout"));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

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

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(5)).write(captor.capture());

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

        var captor = ArgumentCaptor.forClass(String.class);
        // progress + 2 tokens + complete = 4
        verify(resource, times(4)).write(captor.capture());

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

        var captor = ArgumentCaptor.forClass(String.class);
        // progress + 1 token + error = 3
        verify(resource, times(3)).write(captor.capture());

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

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, atLeast(2)).write(captor.capture());

        assertTrue(session.isClosed());
    }

    // --- New tests below ---

    @Test
    public void testResponseHandlerOnPartialResponseWithEmptyString() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onPartialResponse("");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(1)).write(captor.capture());

        var msg = captor.getValue();
        assertTrue(msg.contains("\"type\":\"token\""));
        assertTrue(msg.contains("\"data\":\"\""));
    }

    @Test
    public void testResponseHandlerOnPartialResponseWithSpecialCharacters() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onPartialResponse("He said \"hello\" & goodbye");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(1)).write(captor.capture());

        var msg = captor.getValue();
        assertTrue(msg.contains("\"type\":\"token\""));
        // JSON should properly escape the quotes
        assertTrue(msg.contains("hello"));
    }

    @Test
    public void testResponseHandlerOnErrorWithNullMessage() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        // Exception with null message
        handler.onError(new RuntimeException((String) null));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

        var msg = captor.getValue();
        assertTrue(msg.contains("\"type\":\"error\""));
        assertTrue(session.isClosed());
    }

    @Test
    public void testResponseHandlerOnCompleteWithNullAiMessage() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        // Build a response without an AiMessage text by using empty message
        var response = ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .build();

        handler.onCompleteResponse(response);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

        // Session should be closed
        assertTrue(session.isClosed());
    }

    @Test
    public void testSessionClosedAfterError() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        assertFalse(session.isClosed());
        handler.onError(new RuntimeException("fail"));
        assertTrue(session.isClosed());
    }

    @Test
    public void testSessionClosedAfterComplete() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        assertFalse(session.isClosed());
        handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("done")).build());
        assertTrue(session.isClosed());
    }

    @Test
    public void testSendAfterCompleteIsIgnored() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("done")).build());

        // Reset to track only new writes
        reset(resource);
        when(resource.write(anyString())).thenReturn(resource);

        // Session is closed, so this send should be silently ignored
        handler.onPartialResponse("should be ignored");

        // No new writes because session is closed
        verify(resource, never()).write(anyString());
    }

    @Test
    public void testSendAfterErrorIsIgnored() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onError(new RuntimeException("fail"));

        // Reset to track only new writes
        reset(resource);
        when(resource.write(anyString())).thenReturn(resource);

        // Session is closed after error
        handler.onPartialResponse("should be ignored");

        verify(resource, never()).write(anyString());
    }

    @Test
    public void testAdapterSendsProgressMessageFirst() {
        var model = mock(StreamingChatLanguageModel.class);
        var chatRequest = mock(ChatRequest.class);

        // Model does nothing (simulates a model that hasn't responded yet)
        doNothing().when(model).chat(eq(chatRequest), any(StreamingChatResponseHandler.class));

        adapter.stream(model, chatRequest, session);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(1)).write(captor.capture());

        var msg = captor.getValue();
        assertTrue(msg.contains("\"type\":\"progress\""));
        assertTrue(msg.contains("Connecting to AI model..."));
    }

    @Test
    public void testLangChain4jRequestRecordAccessors() {
        var model = mock(StreamingChatLanguageModel.class);
        var chatRequest = mock(ChatRequest.class);

        var request = new LangChain4jStreamingAdapter.LangChain4jRequest(model, chatRequest);

        assertSame(request.model(), model);
        assertSame(request.chatRequest(), chatRequest);
    }

    @Test
    public void testLangChain4jRequestRecordEquality() {
        var model = mock(StreamingChatLanguageModel.class);
        var chatRequest = mock(ChatRequest.class);

        var request1 = new LangChain4jStreamingAdapter.LangChain4jRequest(model, chatRequest);
        var request2 = new LangChain4jStreamingAdapter.LangChain4jRequest(model, chatRequest);

        assertEquals(request1, request2);
        assertEquals(request1.hashCode(), request2.hashCode());
    }

    @Test
    public void testSequenceNumbersIncrease() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onPartialResponse("A");
        handler.onPartialResponse("B");
        handler.onPartialResponse("C");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(3)).write(captor.capture());

        var messages = captor.getAllValues();
        // Extract sequence numbers and verify they are increasing
        long prevSeq = -1;
        for (var msg : messages) {
            int idx = msg.indexOf("\"seq\":");
            assertTrue(idx > 0, "Message should contain seq field");
            int start = idx + 6;
            int end = msg.indexOf("}", start);
            if (end < 0) end = msg.indexOf(",", start);
            long seq = Long.parseLong(msg.substring(start, end).trim());
            assertTrue(seq > prevSeq, "Sequence " + seq + " should be greater than " + prevSeq);
            prevSeq = seq;
        }
    }

    @Test
    public void testSessionIdIncludedInMessages() {
        var handler = new AtmosphereStreamingResponseHandler(session);

        handler.onPartialResponse("token");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

        var msg = captor.getValue();
        assertTrue(msg.contains("\"sessionId\":\"test-session\""));
    }

    @Test
    public void testAdapterConvenienceMethodDelegates() {
        var model = mock(StreamingChatLanguageModel.class);
        var chatRequest = mock(ChatRequest.class);

        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());
            return null;
        }).when(model).chat(eq(chatRequest), any(StreamingChatResponseHandler.class));

        // Use the 3-arg convenience method
        adapter.stream(model, chatRequest, session);

        // Verify the model was actually called
        verify(model).chat(eq(chatRequest), any(StreamingChatResponseHandler.class));
        assertTrue(session.isClosed());
    }
}
