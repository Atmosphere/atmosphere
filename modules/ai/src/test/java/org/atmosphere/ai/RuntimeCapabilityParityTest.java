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

import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end parity test for the "write once, run anywhere" contract.
 * Exercises the full BuiltInAgentRuntime pipeline: progress events,
 * message assembly, tool calling, usage metadata, and completion.
 *
 * <p>This proves that an {@code @Agent} with {@code @AiTool} methods works
 * on the built-in runtime (zero framework dependencies).</p>
 */
class RuntimeCapabilityParityTest {

    @SuppressWarnings("unchecked")
    @Test
    void builtInRuntimeFullToolCallingPipeline() throws Exception {
        // --- Mock LLM responses ---
        // Round 1: model requests the get_time tool
        var toolCallResponse = """
                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{\\\"city\\\":\\\"Montreal\\\"}"}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        // Round 2: model generates final text with usage metadata
        var textResponse = """
                data: {"id":"chatcmpl-2","model":"gpt-4","choices":[{"index":0,"delta":{"content":"It is 3pm in Montreal."},"finish_reason":null}]}

                data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":42,"completion_tokens":8,"total_tokens":50}}

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

        // --- Configure runtime with injectable client ---
        var runtime = new TestableBuiltInRuntime(client);

        // --- Define tool ---
        var toolCalled = new AtomicBoolean(false);
        var toolArgs = new AtomicReference<Map<String, Object>>();
        var tool = ToolDefinition.builder("get_time", "Get current time for a city")
                .parameter("city", "The city name", "string")
                .executor(args -> {
                    toolCalled.set(true);
                    toolArgs.set(args);
                    return "3:00 PM EST";
                })
                .build();

        // --- Build context ---
        var context = new AgentExecutionContext(
                "What time is it in Montreal?",
                "You are a helpful assistant",
                "gpt-4", "test-agent", "session-1", "user-1", "conv-1",
                List.of(tool), null, null, List.of(),
                Map.of(),
                List.of(new org.atmosphere.ai.llm.ChatMessage("user", "Hello"),
                        new org.atmosphere.ai.llm.ChatMessage("assistant", "Hi there!")),
                null,
                null
        );

        // --- Capture session events ---
        var progressMessages = new ArrayList<String>();
        var textChunks = new ArrayList<String>();
        var metadataMap = new ConcurrentHashMap<String, Object>();
        var completed = new CountDownLatch(1);
        var errors = new ArrayList<Throwable>();

        var session = new StreamingSession() {
            @Override public String sessionId() { return "parity-test"; }
            @Override public void send(String text) { textChunks.add(text); }
            @Override public void sendMetadata(String key, Object value) { metadataMap.put(key, value); }
            @Override public void progress(String message) { progressMessages.add(message); }
            @Override public void complete() { completed.countDown(); }
            @Override public void complete(String summary) { completed.countDown(); }
            @Override public void error(Throwable t) { errors.add(t); completed.countDown(); }
            @Override public boolean isClosed() { return completed.getCount() == 0; }
        };

        // --- Execute ---
        runtime.execute(context, session);

        assertTrue(completed.await(5, TimeUnit.SECONDS), "Should complete within 5s");
        assertTrue(errors.isEmpty(), "No errors expected, got: " + errors);

        // --- Verify: Progress event ---
        assertEquals(1, progressMessages.size());
        assertEquals("Connecting to built-in...", progressMessages.get(0));

        // --- Verify: Tool was called ---
        assertTrue(toolCalled.get(), "Tool should have been called");
        assertEquals("Montreal", toolArgs.get().get("city"));

        // --- Verify: Text response streamed ---
        var fullText = String.join("", textChunks);
        assertTrue(fullText.contains("3pm in Montreal"), "Expected final text, got: " + fullText);

        // --- Verify: Usage metadata ---
        // Phase 1 promoted ad-hoc sendMetadata("ai.tokens.*") to a typed
        // StreamingSession.usage(TokenUsage) event; the default sink re-emits
        // the legacy keys as Long (previously Integer).
        assertEquals(42L, metadataMap.get("ai.tokens.input"));
        assertEquals(8L, metadataMap.get("ai.tokens.output"));
        assertEquals(50L, metadataMap.get("ai.tokens.total"));
        assertEquals("gpt-4", metadataMap.get("ai.model"));

        // --- Verify: 2 HTTP calls (initial + re-submit with tool result) ---
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void allRuntimesDeclareCommonCapabilities() {
        // Every runtime that supports tool calling should also declare
        // STRUCTURED_OUTPUT and SYSTEM_PROMPT.
        var runtime = new BuiltInAgentRuntime();
        var caps = runtime.capabilities();

        assertTrue(caps.contains(AiCapability.TEXT_STREAMING));
        assertTrue(caps.contains(AiCapability.TOOL_CALLING));
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT));

        // All runtimes with TOOL_CALLING should be usable with @AiTool
        var requiredForToolCalling = Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.SYSTEM_PROMPT
        );
        assertTrue(caps.containsAll(requiredForToolCalling),
                "Built-in runtime must declare all capabilities needed for @AiTool: " + requiredForToolCalling);
    }

    @Test
    void assembleMessagesProducesCorrectOrder() {
        var context = new AgentExecutionContext(
                "current",
                "system prompt",
                "gpt-4", null, null, null, null,
                List.of(), null, null, List.of(),
                Map.of(),
                List.of(
                        new org.atmosphere.ai.llm.ChatMessage("user", "q1"),
                        new org.atmosphere.ai.llm.ChatMessage("assistant", "a1")
                ),
                null,
                null
        );

        var messages = AbstractAgentRuntime.assembleMessages(context);

        // Order: system, history (user, assistant), current user
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("system prompt", messages.get(0).content());
        assertEquals("user", messages.get(1).role());
        assertEquals("q1", messages.get(1).content());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("a1", messages.get(2).content());
        assertEquals("user", messages.get(3).role());
        assertEquals("current", messages.get(3).content());
    }

    /** Exposes setNativeClient for testing. */
    static class TestableBuiltInRuntime extends BuiltInAgentRuntime {
        TestableBuiltInRuntime(org.atmosphere.ai.llm.LlmClient client) {
            setNativeClient(client);
        }
    }

    // --- Mock helpers ---

    private record MockResponse(int statusCode, String body) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private HttpClient mockHttpClientSequence(MockResponse... responses) throws Exception {
        var httpClient = mock(HttpClient.class);
        HttpResponse<java.io.InputStream>[] mocks = new HttpResponse[responses.length];
        for (int i = 0; i < responses.length; i++) {
            var mr = responses[i];
            HttpResponse<java.io.InputStream> resp = mock(HttpResponse.class);
            doReturn(mr.statusCode()).when(resp).statusCode();
            doReturn(new ByteArrayInputStream(mr.body().getBytes(StandardCharsets.UTF_8)))
                    .when(resp).body();
            doReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true))
                    .when(resp).headers();
            mocks[i] = resp;
        }
        var first = mocks[0];
        var rest = java.util.Arrays.copyOfRange(mocks, 1, mocks.length);
        doReturn(first, (Object[]) rest)
                .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return httpClient;
    }
}
