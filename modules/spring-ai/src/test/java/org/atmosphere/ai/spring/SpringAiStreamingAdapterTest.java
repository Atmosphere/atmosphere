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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereResource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class SpringAiStreamingAdapterTest {

    private AtmosphereResource resource;
    private StreamingSession session;
    private SpringAiStreamingAdapter adapter;

    @BeforeMethod
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("resource-1");
        when(resource.write(anyString())).thenReturn(resource);
        session = StreamingSessions.start("test-session", resource);
        adapter = new SpringAiStreamingAdapter();
    }

    @Test
    public void testName() {
        assertEquals(adapter.name(), "spring-ai");
    }

    @Test
    public void testStreamTokens() throws Exception {
        var latch = new CountDownLatch(1);

        var responses = List.of(
                chatResponse("Hello"),
                chatResponse(" world"),
                chatResponse("!")
        );

        Flux<ChatResponse> flux = Flux.fromIterable(responses)
                .doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("test prompt", flux);

        adapter.stream(client, "test prompt", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Stream should complete");

        var captor = ArgumentCaptor.forClass(String.class);
        // 1 progress + 3 tokens + 1 complete = 5
        verify(resource, timeout(2000).atLeast(5)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();

        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"progress\"")),
                "Should send progress message");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"Hello\"")),
                "Should send 'Hello' token");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\" world\"")),
                "Should send ' world' token");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"!\"")),
                "Should send '!' token");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"complete\"")),
                "Should send complete message");
    }

    @Test
    public void testStreamWithNullOutput() throws Exception {
        var latch = new CountDownLatch(1);

        // ChatResponse with null generation result
        var response = new ChatResponse(List.of());
        Flux<ChatResponse> flux = Flux.just(response).doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("test", flux);
        adapter.stream(client, "test", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        // progress + complete = 2 (no tokens because getResult() is null)
        verify(resource, timeout(2000).atLeast(2)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();
        // Should NOT have any token messages
        assertFalse(messages.stream().anyMatch(m ->
                        m.contains("\"type\":\"token\"") && m.contains("\"data\"")),
                "Should not send token for null output");
    }

    @Test
    public void testStreamError() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.<ChatResponse>error(new RuntimeException("Model unavailable"))
                .doOnError(t -> latch.countDown());

        ChatClient client = mockChatClient("test", flux);
        adapter.stream(client, "test", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        // progress + error = 2
        verify(resource, timeout(2000).atLeast(2)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"error\"")),
                "Should send error message");
        assertTrue(messages.stream().anyMatch(m -> m.contains("Model unavailable")),
                "Should include error details");
    }

    @Test
    public void testStreamViaAdapterInterface() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.just(chatResponse("Hi"))
                .doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("prompt", flux);

        // Use the generic AiStreamingAdapter.stream() interface
        adapter.stream(new SpringAiStreamingAdapter.ChatRequest(client, "prompt"), session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, timeout(2000).atLeast(3)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"Hi\"")));
    }

    @Test
    public void testStreamMultipleTokensThenComplete() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.just(
                chatResponse("The"),
                chatResponse(" answer"),
                chatResponse(" is"),
                chatResponse(" 42")
        ).doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("question", flux);
        adapter.stream(client, "question", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, timeout(2000).atLeast(6)).write(captor.capture());

        // Verify sequence numbers are monotonically increasing
        var seqValues = captor.getAllValues().stream()
                .map(Object::toString)
                .filter(m -> m.contains("\"seq\":"))
                .map(m -> {
                    int idx = m.indexOf("\"seq\":");
                    int start = idx + 6;
                    int end = m.indexOf("}", start);
                    if (end < 0) end = m.indexOf(",", start);
                    if (end < 0) end = m.length();
                    return Long.parseLong(m.substring(start, end).trim());
                })
                .toList();

        for (int i = 1; i < seqValues.size(); i++) {
            assertTrue(seqValues.get(i) > seqValues.get(i - 1),
                    "Sequence numbers should be monotonically increasing");
        }

        assertTrue(session.isClosed(), "Session should be closed after complete");
    }

    // --- New tests below ---

    @Test
    public void testStreamEmptyFlux() throws Exception {
        var latch = new CountDownLatch(1);

        // Empty flux - no tokens at all
        Flux<ChatResponse> flux = Flux.<ChatResponse>empty()
                .doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("empty", flux);
        adapter.stream(client, "empty", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        // progress + complete = 2
        verify(resource, timeout(2000).atLeast(2)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"progress\"")),
                "Should send progress first");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"complete\"")),
                "Should send complete even with empty flux");
    }

    @Test
    public void testStreamWithNullText() throws Exception {
        var latch = new CountDownLatch(1);

        // Generation with null text output
        var generation = new Generation(new AssistantMessage(null));
        var response = new ChatResponse(List.of(generation));
        Flux<ChatResponse> flux = Flux.just(response)
                .doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("null-text", flux);
        adapter.stream(client, "null-text", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, timeout(2000).atLeast(2)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();

        // Should not have sent any token with null text (filtered by the null check)
        assertFalse(messages.stream().anyMatch(m ->
                        m.contains("\"type\":\"token\"") && m.contains("\"data\":null")),
                "Should not send token with null data");
    }

    @Test
    public void testStreamProgressMessage() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.just(chatResponse("token"))
                .doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("progress-test", flux);
        adapter.stream(client, "progress-test", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, timeout(2000).atLeast(3)).write(captor.capture());

        // First message should be the progress message
        var firstMsg = captor.getAllValues().get(0);
        assertTrue(firstMsg.contains("\"type\":\"progress\""),
                "First message should be progress");
        assertTrue(firstMsg.contains("Connecting to AI model..."),
                "Progress should contain connecting message");
    }

    @Test
    public void testChatRequestRecordAccessors() {
        var client = mock(ChatClient.class);
        var request = new SpringAiStreamingAdapter.ChatRequest(client, "my prompt");

        assertSame(request.client(), client);
        assertEquals(request.prompt(), "my prompt");
    }

    @Test
    public void testChatRequestRecordEquality() {
        var client = mock(ChatClient.class);

        var request1 = new SpringAiStreamingAdapter.ChatRequest(client, "prompt");
        var request2 = new SpringAiStreamingAdapter.ChatRequest(client, "prompt");

        assertEquals(request1, request2);
        assertEquals(request1.hashCode(), request2.hashCode());
    }

    @Test
    public void testChatRequestRecordInequality() {
        var client1 = mock(ChatClient.class);
        var client2 = mock(ChatClient.class);

        var request1 = new SpringAiStreamingAdapter.ChatRequest(client1, "prompt");
        var request2 = new SpringAiStreamingAdapter.ChatRequest(client2, "prompt");

        assertNotEquals(request1, request2);
    }

    @Test
    public void testSessionIdIncludedInMessages() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.just(chatResponse("data"))
                .doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("sid-test", flux);
        adapter.stream(client, "sid-test", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, timeout(2000).atLeast(3)).write(captor.capture());

        // All messages should include the session ID
        for (var msg : captor.getAllValues()) {
            assertTrue(msg.contains("\"sessionId\":\"test-session\""),
                    "All messages should include sessionId");
        }
    }

    @Test
    public void testStreamWithSpecialCharacters() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.just(
                chatResponse("He said \"hello\""),
                chatResponse(" & goodbye")
        ).doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("special", flux);
        adapter.stream(client, "special", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, timeout(2000).atLeast(4)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();
        // JSON encoding should handle quotes and ampersand
        assertTrue(messages.stream().anyMatch(m -> m.contains("hello")),
                "Should contain 'hello'");
        assertTrue(messages.stream().anyMatch(m -> m.contains("goodbye")),
                "Should contain 'goodbye'");
    }

    @Test
    public void testStreamErrorAfterTokens() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.concat(
                Flux.just(chatResponse("partial")),
                Flux.<ChatResponse>error(new RuntimeException("Connection lost"))
        ).doOnError(t -> latch.countDown());

        ChatClient client = mockChatClient("err-after-tokens", flux);
        adapter.stream(client, "err-after-tokens", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var captor = ArgumentCaptor.forClass(String.class);
        // progress + 1 token + error = 3
        verify(resource, timeout(2000).atLeast(3)).write(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(Object::toString)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"partial\"")),
                "Should have sent the partial token before error");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"error\"")),
                "Should send error after partial tokens");
        assertTrue(messages.stream().anyMatch(m -> m.contains("Connection lost")),
                "Error message should contain cause");
    }

    @Test
    public void testSessionClosedAfterStreamComplete() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.just(chatResponse("done"))
                .doOnComplete(latch::countDown);

        ChatClient client = mockChatClient("closed-test", flux);

        assertFalse(session.isClosed(), "Session should be open before streaming");

        adapter.stream(client, "closed-test", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Allow time for the doOnComplete callback to fire
        verify(resource, timeout(2000).atLeast(3)).write(anyString());

        assertTrue(session.isClosed(), "Session should be closed after streaming completes");
    }

    @Test
    public void testSessionClosedAfterStreamError() throws Exception {
        var latch = new CountDownLatch(1);

        Flux<ChatResponse> flux = Flux.<ChatResponse>error(new RuntimeException("fail"))
                .doOnError(t -> latch.countDown());

        ChatClient client = mockChatClient("err-close-test", flux);

        assertFalse(session.isClosed());

        adapter.stream(client, "err-close-test", session);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        verify(resource, timeout(2000).atLeast(2)).write(anyString());

        assertTrue(session.isClosed(), "Session should be closed after error");
    }

    @SuppressWarnings("null")
    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @SuppressWarnings("null")
    private static ChatClient mockChatClient(String prompt, Flux<ChatResponse> flux) {
        ChatClient client = mock(ChatClient.class);
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(client.prompt(prompt)).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(flux);

        return client;
    }
}
