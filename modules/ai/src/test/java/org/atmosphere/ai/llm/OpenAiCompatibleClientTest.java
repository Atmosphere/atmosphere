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
import java.util.Set;
import java.util.concurrent.Future;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"unchecked", "rawtypes"})
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
        when(broadcaster.broadcast(any(RawMessage.class), any(Set.class))).thenReturn(mock(Future.class));
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
                .maxStreamingTexts(1024)
                .build();

        assertEquals("gpt-4", request.model());
        assertEquals(2, request.messages().size());
        assertEquals("system", request.messages().get(0).role());
        assertEquals("user", request.messages().get(1).role());
        assertEquals(0.5, request.temperature());
        assertEquals(1024, request.maxStreamingTexts());
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
        verify(broadcaster, atLeast(3)).broadcast(captor.capture(), any(Set.class));

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();

        // Should have: "Hello", " world", "!", complete
        // Progress event is now emitted by AbstractAgentRuntime, not the client
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
        verify(broadcaster, atLeast(1)).broadcast(captor.capture(), any(Set.class));

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
        verify(broadcaster, atLeast(3)).broadcast(captor.capture(), any(Set.class));

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"metadata\"") && m.contains("ai.tokens.total")));
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
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any(Set.class));

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        // Only "OK" should be sent as a streaming text, not empty string
        long streamingTextCount = messages.stream().filter(m -> m.contains("\"type\":\"streaming-text\"")).count();
        assertEquals(1, streamingTextCount);
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
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any(Set.class));
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
        verify(broadcaster, atLeast(1)).broadcast(captor.capture(), any(Set.class));
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
        verify(broadcaster, atLeast(1)).broadcast(captor.capture(), any(Set.class));
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
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any(Set.class));
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
    public void testToolCallingSSE() throws Exception {
        // First response: model requests a tool call
        var toolCallResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\\"city\\\":"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"Montreal\\\"}"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        // Second response: model generates text after tool result
        var textResponse = """
                data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{"content":"It is 22C"},"finish_reason":null}]}

                data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(200, toolCallResponse),
                new MockResponse(200, textResponse)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var tool = org.atmosphere.ai.tool.ToolDefinition.builder("get_weather", "Get weather for a city")
                .parameter("city", "The city name", "string")
                .executor(args -> "22C and sunny in " + args.get("city"))
                .build();

        var request = ChatCompletionRequest.builder("test-model")
                .user("What's the weather in Montreal?")
                .tools(java.util.List.of(tool))
                .build();

        var session = StreamingSessions.start("test-tools", resource);
        client.streamChatCompletion(request, session);

        // Should have made 2 HTTP calls: initial + re-submit after tool execution
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(3)).broadcast(captor.capture(), any(Set.class));

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();

        // Should have tool-start, tool-result, text, complete events
        assertTrue(messages.stream().anyMatch(m -> m.contains("tool-start") && m.contains("get_weather")),
                "Expected tool-start event");
        assertTrue(messages.stream().anyMatch(m -> m.contains("tool-result") && m.contains("get_weather")),
                "Expected tool-result event");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"It is 22C\"")),
                "Expected text response after tool call");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"complete\"")),
                "Expected complete event");
    }

    @Test
    public void testToolsSerializedInRequest() {
        var tool = org.atmosphere.ai.tool.ToolDefinition.builder("search", "Search for info")
                .parameter("query", "Search query", "string")
                .parameter("limit", "Max results", "integer", false)
                .executor(args -> "result")
                .build();

        var request = ChatCompletionRequest.builder("gpt-4")
                .user("Search for Atmosphere")
                .tools(java.util.List.of(tool))
                .build();

        assertEquals(1, request.tools().size());
        assertEquals("search", request.tools().get(0).name());
        assertFalse(request.tools().isEmpty());
    }

    @Test
    public void testToolMessageFactory() {
        var msg = ChatMessage.tool("result text", "call_123");
        assertEquals("tool", msg.role());
        assertEquals("result text", msg.content());
        assertEquals("call_123", msg.toolCallId());
    }

    @Test
    public void testChatMessageBackwardsCompatibility() {
        // 2-arg constructor should still work
        var msg = new ChatMessage("user", "hello");
        assertEquals("user", msg.role());
        assertEquals("hello", msg.content());
        assertNull(msg.toolCallId());
    }

    @Test
    public void testMaxToolRoundsRespected() throws Exception {
        // Build a response that always requests tool calls
        var toolCallResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"echo","arguments":"{}"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        // 6 responses: 5 tool call rounds + should never reach 6th
        var responses = new MockResponse[6];
        for (int i = 0; i < 6; i++) {
            responses[i] = new MockResponse(200, toolCallResponse);
        }
        var httpClient = mockHttpClientSequence(responses);

        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var tool = org.atmosphere.ai.tool.ToolDefinition.builder("echo", "Echo tool")
                .executor(args -> "echoed")
                .build();

        var request = ChatCompletionRequest.builder("test")
                .user("loop")
                .tools(java.util.List.of(tool))
                .build();

        var session = StreamingSessions.start("test-max-rounds", resource);
        client.streamChatCompletion(request, session);

        // Should stop after MAX_TOOL_ROUNDS (5) + 1 initial = 6 calls max
        // But since round 5 hits the limit, it should be exactly 6 HTTP calls
        verify(httpClient, atMost(6)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testMultipleSimultaneousToolCalls() throws Exception {
        // Model requests 2 tool calls in a single response
        var toolCallResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}},{"index":1,"id":"call_2","type":"function","function":{"name":"get_city_time","arguments":""}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\\"city\\\":\\\"Paris\\\"}"}},{"index":1,"function":{"arguments":"{\\\"city\\\":\\\"Tokyo\\\"}"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        var textResponse = """
                data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{"content":"Paris: cloudy. Tokyo: 3am."},"finish_reason":null}]}

                data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(200, toolCallResponse),
                new MockResponse(200, textResponse)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var weatherTool = org.atmosphere.ai.tool.ToolDefinition.builder("get_weather", "Weather")
                .parameter("city", "City", "string")
                .executor(args -> "Cloudy in " + args.get("city"))
                .build();
        var timeTool = org.atmosphere.ai.tool.ToolDefinition.builder("get_city_time", "Time")
                .parameter("city", "City", "string")
                .executor(args -> "3:00 AM in " + args.get("city"))
                .build();

        var request = ChatCompletionRequest.builder("test")
                .user("Weather and time?")
                .tools(java.util.List.of(weatherTool, timeTool))
                .build();

        var session = StreamingSessions.start("test-multi-tools", resource);
        client.streamChatCompletion(request, session);

        // 2 HTTP calls: initial + re-submit
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(4)).broadcast(captor.capture(), any(Set.class));

        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();

        // Both tools should have been called
        assertTrue(messages.stream().anyMatch(m -> m.contains("tool-start") && m.contains("get_weather")),
                "Expected get_weather tool-start");
        assertTrue(messages.stream().anyMatch(m -> m.contains("tool-start") && m.contains("get_city_time")),
                "Expected get_city_time tool-start");
        assertTrue(messages.stream().anyMatch(m -> m.contains("tool-result") && m.contains("get_weather")),
                "Expected get_weather tool-result");
        assertTrue(messages.stream().anyMatch(m -> m.contains("tool-result") && m.contains("get_city_time")),
                "Expected get_city_time tool-result");
    }

    @Test
    public void testToolResultSerializedWithToolCallId() throws Exception {
        // Verify that the re-submitted request includes tool messages with tool_call_id
        var toolCallResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_xyz","type":"function","function":{"name":"echo","arguments":"{\\\"msg\\\":\\\"hi\\\"}"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        var textResponse = """
                data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(200, toolCallResponse),
                new MockResponse(200, textResponse)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var tool = org.atmosphere.ai.tool.ToolDefinition.builder("echo", "Echo")
                .parameter("msg", "Message", "string")
                .executor(args -> "echoed: " + args.get("msg"))
                .build();

        var request = ChatCompletionRequest.builder("test")
                .user("echo hi")
                .tools(java.util.List.of(tool))
                .build();

        var session = StreamingSessions.start("test-tool-id", resource);
        client.streamChatCompletion(request, session);

        // Capture the second HTTP request to verify tool_call_id is in the body
        var reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));

        // The second request should contain the tool result with tool_call_id
        var secondRequest = reqCaptor.getAllValues().get(1);
        assertNotNull(secondRequest, "Expected a second HTTP request for tool result re-submission");
    }

    @Test
    public void testNoToolCallsWhenNoToolsProvided() throws Exception {
        // Even if model returns tool_calls, if no tools were provided, should complete normally
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        // No tools in request
        var request = ChatCompletionRequest.of("test", "Hi");
        var session = StreamingSessions.start("test-no-tools", resource);
        client.streamChatCompletion(request, session);

        // Only 1 HTTP call
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any(Set.class));
        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"Hello\"")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"complete\"")));
    }

    @Test
    public void testToolNotFoundHandledGracefully() throws Exception {
        // Model requests a tool that doesn't exist in our registry
        var toolCallResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"unknown_tool","arguments":"{}"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        var textResponse = """
                data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{"content":"Sorry"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(200, toolCallResponse),
                new MockResponse(200, textResponse)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        // Register a different tool than what the model requests
        var tool = org.atmosphere.ai.tool.ToolDefinition.builder("existing_tool", "Exists")
                .executor(args -> "result")
                .build();

        var request = ChatCompletionRequest.builder("test")
                .user("call unknown")
                .tools(java.util.List.of(tool))
                .build();

        var session = StreamingSessions.start("test-unknown-tool", resource);
        client.streamChatCompletion(request, session);

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any(Set.class));
        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();

        // Should emit tool-error for the unknown tool
        assertTrue(messages.stream().anyMatch(m -> m.contains("tool-error") && m.contains("unknown_tool")),
                "Expected tool-error event for unknown tool");
    }

    // -- OpenAI Responses API tests --

    @Test
    public void testResponsesApiFirstTurnCachesResponseId() throws Exception {
        // Simulate a Responses API SSE stream on the first turn
        var responsesApiStream = """
                data: {"type":"response.output_text.delta","delta":"Hello"}

                data: {"type":"response.output_text.delta","delta":" there"}

                data: {"type":"response.completed","response":{"id":"resp_abc123","model":"gpt-4o","usage":{"input_tokens":10,"output_tokens":5}}}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, responsesApiStream);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();

        var request = ChatCompletionRequest.builder("gpt-4o")
                .system("You are helpful")
                .user("Hello!")
                .conversationId("conv-1")
                .build();

        var session = StreamingSessions.start("test-resp-api", resource);
        client.streamChatCompletion(request, session);

        // Verify text was streamed
        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any(Set.class));
        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"Hello\"")),
                "Expected 'Hello' text chunk");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\" there\"")),
                "Expected ' there' text chunk");

        // Verify response ID was cached
        assertEquals("resp_abc123", client.responseIdCache().get("conv-1"),
                "Response ID should be cached for conversation");
    }

    @Test
    public void testResponsesApiContinuationWithPreviousId() throws Exception {
        // First turn response
        var firstResponse = """
                data: {"type":"response.output_text.delta","delta":"Hi"}

                data: {"type":"response.completed","response":{"id":"resp_first","model":"gpt-4o","usage":{"input_tokens":5,"output_tokens":2}}}

                data: [DONE]

                """;

        // Second turn response
        var secondResponse = """
                data: {"type":"response.output_text.delta","delta":"Sure"}

                data: {"type":"response.completed","response":{"id":"resp_second","model":"gpt-4o","usage":{"input_tokens":3,"output_tokens":2}}}

                data: [DONE]

                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(200, firstResponse),
                new MockResponse(200, secondResponse)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();

        // First turn
        var request1 = ChatCompletionRequest.builder("gpt-4o")
                .user("Hello")
                .conversationId("conv-2")
                .build();
        var session1 = StreamingSessions.start("test-resp-1", resource);
        client.streamChatCompletion(request1, session1);

        // Verify first response cached
        assertEquals("resp_first", client.responseIdCache().get("conv-2"));

        // Second turn — should use previous_response_id
        var request2 = ChatCompletionRequest.builder("gpt-4o")
                .user("Tell me more")
                .conversationId("conv-2")
                .build();
        var session2 = StreamingSessions.start("test-resp-2", resource);
        client.streamChatCompletion(request2, session2);

        // Verify response ID updated
        assertEquals("resp_second", client.responseIdCache().get("conv-2"));

        // Verify both turns made HTTP calls
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // Verify the second request went to /responses endpoint
        var reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var secondReq = reqCaptor.getAllValues().get(1);
        assertTrue(secondReq.uri().getPath().endsWith("/responses"),
                "Second request should use /responses endpoint");
    }

    @Test
    public void testResponsesApiNotUsedForNonOpenAi() throws Exception {
        // Non-OpenAI endpoints should not use the Responses API
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://localhost:11434/v1")
                .httpClient(httpClient)
                .build();

        var request = ChatCompletionRequest.builder("test")
                .user("Hello")
                .conversationId("conv-3")
                .build();
        var session = StreamingSessions.start("test-non-openai", resource);
        client.streamChatCompletion(request, session);

        // Should use /chat/completions, not /responses
        var reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(reqCaptor.getValue().uri().getPath().endsWith("/chat/completions"),
                "Non-OpenAI endpoint should use /chat/completions");

        // Response ID cache should be empty
        assertTrue(client.responseIdCache().isEmpty(),
                "No response ID should be cached for non-OpenAI endpoints");
    }

    @Test
    public void testResponsesApiNotUsedWithoutConversationId() throws Exception {
        var sseResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, sseResponse);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();

        // No conversationId
        var request = ChatCompletionRequest.of("gpt-4o", "Hello");
        var session = StreamingSessions.start("test-no-convid", resource);
        client.streamChatCompletion(request, session);

        // Should use /chat/completions
        var reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(reqCaptor.getValue().uri().getPath().endsWith("/chat/completions"),
                "Requests without conversationId should use /chat/completions");
    }

    @Test
    public void testResponsesApi404FallsBackToChatCompletions() throws Exception {
        // Simulate a 404 from Responses API (cache miss) followed by Chat Completions success
        var notFoundBody = """
                {"error":{"message":"Response not found","type":"not_found"}}
                """;
        var chatCompletionsResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Fallback OK"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        var httpClient = mockHttpClientSequence(
                new MockResponse(404, notFoundBody),
                new MockResponse(200, chatCompletionsResponse)
        );
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();

        // Seed a stale response ID
        client.seedResponseId("conv-404", "resp_stale");

        var request = ChatCompletionRequest.builder("gpt-4o")
                .user("Hello")
                .conversationId("conv-404")
                .build();
        var session = StreamingSessions.start("test-404-fallback", resource);
        client.streamChatCompletion(request, session);

        // Should have made 2 requests: Responses API (404) + Chat Completions (200)
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // Stale response ID should have been evicted
        assertNull(client.responseIdCache().get("conv-404"),
                "Stale response ID should be evicted after 404");

        // Should have received the fallback text
        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any(Set.class));
        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"data\":\"Fallback OK\"")),
                "Expected fallback text from Chat Completions");
    }

    @Test
    public void testResponsesApiUsageMetadata() throws Exception {
        var responsesApiStream = """
                data: {"type":"response.output_text.delta","delta":"Hi"}

                data: {"type":"response.completed","response":{"id":"resp_usage","model":"gpt-4o","usage":{"input_tokens":12,"output_tokens":8}}}

                data: [DONE]

                """;

        var httpClient = mockHttpClient(200, responsesApiStream);
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();

        var request = ChatCompletionRequest.builder("gpt-4o")
                .user("Hi")
                .conversationId("conv-usage")
                .build();
        var session = StreamingSessions.start("test-resp-usage", resource);
        client.streamChatCompletion(request, session);

        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster, atLeast(3)).broadcast(captor.capture(), any(Set.class));
        var messages = captor.getAllValues().stream().map(m -> raw(m)).toList();
        assertTrue(messages.stream().anyMatch(m ->
                        m.contains("\"type\":\"metadata\"") && m.contains("ai.tokens.total")),
                "Expected usage metadata with token counts");
    }

    @Test
    public void testSeedAndEvictResponseId() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .build();

        assertNull(client.responseIdCache().get("conv-x"));

        client.seedResponseId("conv-x", "resp_123");
        assertEquals("resp_123", client.responseIdCache().get("conv-x"));

        client.evictResponseId("conv-x");
        assertNull(client.responseIdCache().get("conv-x"));
    }

    @Test
    public void testConversationIdInRequestBuilder() {
        var request = ChatCompletionRequest.builder("gpt-4o")
                .user("Hello")
                .conversationId("conv-test")
                .build();
        assertEquals("conv-test", request.conversationId());
    }

    @Test
    public void testConversationIdNullByDefault() {
        var request = ChatCompletionRequest.of("gpt-4o", "Hello");
        assertNull(request.conversationId());
    }

    @Test
    public void testBackwardsCompatibleConstructor() {
        // The 6-arg constructor (without conversationId) should still work
        var request = new ChatCompletionRequest("gpt-4o",
                java.util.List.of(ChatMessage.user("Hi")),
                0.7, 2048, false, java.util.List.of());
        assertNull(request.conversationId());
        assertEquals("gpt-4o", request.model());
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
