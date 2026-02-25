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
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OpenAiCompatibleClientTest {

    private AtmosphereResource resource;
    private Broadcaster broadcaster;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("r1");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(RawMessage.class))).thenReturn(mock(Future.class));
    }

    /** Extract the JSON string from a captured RawMessage. */
    private static String raw(RawMessage msg) {
        return (String) msg.message();
    }

    @Test
    public void testChatMessageFactories() {
        var system = ChatMessage.system("You are helpful");
        assertEquals("system", system.role());
        assertEquals("You are helpful", system.content());

        var user = ChatMessage.user("Hello");
        assertEquals("user", user.role());

        var assistant = ChatMessage.assistant("Hi!");
        assertEquals("assistant", assistant.role());
    }

    @Test
    public void testRequestBuilder() {
        var request = ChatCompletionRequest.builder("gpt-4")
                .system("You are a helpful assistant")
                .user("What is Atmosphere?")
                .temperature(0.5)
                .maxTokens(1024)
                .build();

        assertEquals("gpt-4", request.model());
        assertEquals(2, request.messages().size());
        assertEquals("system", request.messages().get(0).role());
        assertEquals("user", request.messages().get(1).role());
        assertEquals(0.5, request.temperature());
        assertEquals(1024, request.maxTokens());
    }

    @Test
    public void testSimpleRequestFactory() {
        var request = ChatCompletionRequest.of("gemini-2.0-flash", "Hello!");
        assertEquals("gemini-2.0-flash", request.model());
        assertEquals(1, request.messages().size());
        assertEquals("user", request.messages().get(0).role());
        assertEquals("Hello!", request.messages().get(0).content());
    }

    @Test
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

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(3)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();

        // Should have: progress, "Hello", " world", "!", complete
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"progress\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"Hello\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\" world\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"!\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"complete\"")));
    }

    @Test
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

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"error\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Invalid API key")));
    }

    @Test
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

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(3)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"metadata\"") && m.contains("usage.totalTokens")));
    }

    @Test
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

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        // Only "OK" should be sent as a token, not empty string
        long tokenCount = messages.stream().filter(m -> m.contains("\"type\":\"token\"")).count();
        assertEquals(1, tokenCount);
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"OK\"")));
    }

    @Test
    public void testRetryOn429ThenSuccess() throws Exception {
        var errorBody = """
                {"error":{"message":"Rate limited","type":"rate_limit_error"}}
                """;
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(429, errorBody),
                new MockResponse(200, sseResponse)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .maxRetries(2)
                .retryBaseDelay(java.time.Duration.ofMillis(10))
                .build();

        var session = StreamingSessions.start("test-retry-429", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test", "Hi"), session);

        // Should have retried and succeeded
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture());
        assertTrue(captor.getAllValues().stream().map(m -> raw(m)).anyMatch(m -> m.contains("\"data\":\"OK\"")));
    }

    @Test
    public void testNoRetryOn401() throws Exception {
        var errorBody = """
                {"error":{"message":"Invalid key","type":"auth_error"}}
                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(401, errorBody)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .maxRetries(3)
                .retryBaseDelay(java.time.Duration.ofMillis(10))
                .build();

        var session = StreamingSessions.start("test-no-retry-401", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test", "Hi"), session);

        // Should NOT retry on 401
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(1)).broadcast(captor.capture());
        assertTrue(captor.getAllValues().stream().map(m -> raw(m)).anyMatch(m -> m.contains("\"type\":\"error\"")));
    }

    @Test
    public void testRetryExhausted() throws Exception {
        var errorBody = """
                {"error":{"message":"Server error","type":"server_error"}}
                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(503, errorBody),
                new MockResponse(503, errorBody),
                new MockResponse(503, errorBody)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .maxRetries(2)
                .retryBaseDelay(java.time.Duration.ofMillis(10))
                .build();

        var session = StreamingSessions.start("test-retry-exhausted", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test", "Hi"), session);

        // Should have tried 3 times (initial + 2 retries)
        verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(1)).broadcast(captor.capture());
        assertTrue(captor.getAllValues().stream().map(m -> raw(m)).anyMatch(m -> m.contains("\"type\":\"error\"")));
    }

    @Test
    public void testRetryOnConnectionError() throws Exception {
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClientWithIOExceptionThenSuccess(sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .maxRetries(2)
                .retryBaseDelay(java.time.Duration.ofMillis(10))
                .build();

        var session = StreamingSessions.start("test-retry-io", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test", "Hi"), session);

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture());
        assertTrue(captor.getAllValues().stream().map(m -> raw(m)).anyMatch(m -> m.contains("\"data\":\"OK\"")));
    }

    @Test
    public void testZeroRetriesDisablesRetry() throws Exception {
        var errorBody = """
                {"error":{"message":"Server error","type":"server_error"}}
                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(503, errorBody)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .maxRetries(0)
                .build();

        var session = StreamingSessions.start("test-zero-retries", resource);
        client.streamChatCompletion(ChatCompletionRequest.of("test", "Hi"), session);

        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testAdapterName() {
        var client = OpenAiCompatibleClient.builder().build();
        var adapter = new LlmStreamingAdapter(client);
        assertEquals("openai-compatible", adapter.name());
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

    private record MockResponse(int statusCode, String body) {}

    @SuppressWarnings("unchecked")
    private HttpClient mockHttpClientSequence(MockResponse... responses) throws Exception {
        var httpClient = mock(HttpClient.class);
        // Build all mock responses FIRST to avoid nested when() calls
        HttpResponse<java.io.InputStream>[] mocks = new HttpResponse[responses.length];
        for (int i = 0; i < responses.length; i++) {
            var mr = responses[i];
            HttpResponse<java.io.InputStream> resp = mock(HttpResponse.class);
            doReturn(mr.statusCode()).when(resp).statusCode();
            doReturn(new ByteArrayInputStream(mr.body().getBytes(StandardCharsets.UTF_8)))
                    .when(resp).body();
            doReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true))
                    .when(resp).headers();
            mocks[i] = resp;
        }
        // Chain all responses using doReturn with varargs for subsequent returns
        var first = mocks[0];
        var rest = java.util.Arrays.copyOfRange(mocks, 1, mocks.length);
        doReturn(first, (Object[]) rest)
                .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return httpClient;
    }

    @SuppressWarnings("unchecked")
    private HttpClient mockHttpClientWithIOExceptionThenSuccess(String successBody) throws Exception {
        var httpClient = mock(HttpClient.class);
        var successResponse = mock(HttpResponse.class);
        when(successResponse.statusCode()).thenReturn(200);
        when(successResponse.body()).thenReturn(
                new ByteArrayInputStream(successBody.getBytes(StandardCharsets.UTF_8))
        );
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("Connection refused"))
                .thenReturn(successResponse);
        return httpClient;
    }
}
