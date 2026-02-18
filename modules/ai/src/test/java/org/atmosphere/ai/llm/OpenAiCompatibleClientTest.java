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

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereResource;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class OpenAiCompatibleClientTest {

    private AtmosphereResource resource;

    @BeforeMethod
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("r1");
        when(resource.write(anyString())).thenReturn(resource);
    }

    @Test
    public void testChatMessageFactories() {
        var system = ChatMessage.system("You are helpful");
        assertEquals(system.role(), "system");
        assertEquals(system.content(), "You are helpful");

        var user = ChatMessage.user("Hello");
        assertEquals(user.role(), "user");

        var assistant = ChatMessage.assistant("Hi!");
        assertEquals(assistant.role(), "assistant");
    }

    @Test
    public void testRequestBuilder() {
        var request = ChatCompletionRequest.builder("gpt-4")
                .system("You are a helpful assistant")
                .user("What is Atmosphere?")
                .temperature(0.5)
                .maxTokens(1024)
                .build();

        assertEquals(request.model(), "gpt-4");
        assertEquals(request.messages().size(), 2);
        assertEquals(request.messages().get(0).role(), "system");
        assertEquals(request.messages().get(1).role(), "user");
        assertEquals(request.temperature(), 0.5);
        assertEquals(request.maxTokens(), 1024);
    }

    @Test
    public void testSimpleRequestFactory() {
        var request = ChatCompletionRequest.of("gemini-2.0-flash", "Hello!");
        assertEquals(request.model(), "gemini-2.0-flash");
        assertEquals(request.messages().size(), 1);
        assertEquals(request.messages().get(0).role(), "user");
        assertEquals(request.messages().get(0).content(), "Hello!");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSSEParsing() throws Exception {
        // Simulate an SSE stream from an OpenAI-compatible API
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var session = StreamingSessions.start("test-sse", resource);
        var request = ChatCompletionRequest.of("test-model", "Hi");

        client.streamChatCompletion(request, session);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, atLeast(3)).write(captor.capture());

        var messages = captor.getAllValues().stream().toList();

        // Should have: progress, "Hello", " world", "!", complete
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"progress\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"Hello\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\" world\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"!\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"complete\"")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAPIErrorHandling() throws Exception {
        var errorBody = """
                {"error":{"message":"Invalid API key","type":"invalid_request_error","code":"invalid_api_key"}}
                """;

        var httpClient = mockHttpClient(401, errorBody);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var session = StreamingSessions.start("test-error", resource);
        var request = ChatCompletionRequest.of("test-model", "Hi");

        client.streamChatCompletion(request, session);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, atLeast(2)).write(captor.capture());

        var messages = captor.getAllValues().stream().toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"error\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Invalid API key")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUsageMetadata() throws Exception {
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var session = StreamingSessions.start("test-usage", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test", "Hi"), session);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, atLeast(3)).write(captor.capture());

        var messages = captor.getAllValues().stream().toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"metadata\"") && m.contains("usage.totalTokens")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testEmptyDeltaIgnored() throws Exception {
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":""},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":null}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var session = StreamingSessions.start("test-empty", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test", "Hi"), session);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, atLeast(2)).write(captor.capture());

        var messages = captor.getAllValues().stream().toList();
        // Only "OK" should be sent as a token, not empty string
        long tokenCount = messages.stream().filter(m -> m.contains("\"type\":\"token\"")).count();
        assertEquals(tokenCount, 1);
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"OK\"")));
    }

    @Test
    public void testAdapterName() {
        var client = OpenAiCompatibleClient.builder().build();
        var adapter = new LlmStreamingAdapter(client);
        assertEquals(adapter.name(), "openai-compatible");
    }

    @Test
    public void testGeminiFactory() {
        var client = OpenAiCompatibleClient.gemini("test-key");
        assertNotNull(client);
    }

    @Test
    public void testOllamaFactory() {
        var client = OpenAiCompatibleClient.ollama();
        assertNotNull(client);
    }

    @SuppressWarnings("unchecked")
    private HttpClient mockHttpClient(int statusCode, String body) throws Exception {
        var httpClient = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        );
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return httpClient;
    }
}
