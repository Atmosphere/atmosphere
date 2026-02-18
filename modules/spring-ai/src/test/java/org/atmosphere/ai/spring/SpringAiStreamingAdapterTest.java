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
