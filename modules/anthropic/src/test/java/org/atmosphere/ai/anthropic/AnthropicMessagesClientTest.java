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
package org.atmosphere.ai.anthropic;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the Anthropic Messages client: SSE text streaming,
 * tool_use → tool_result round-trip, custom-header passthrough, and
 * non-2xx error propagation. Mocks {@link HttpClient} so every assertion
 * runs without touching the network.
 */
class AnthropicMessagesClientTest {

    private static final String TEXT_RESPONSE = """
            data: {"type":"message_start","message":{"id":"msg_1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Bonjour"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" Atmosphere"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":7,"output_tokens":2}}

            data: {"type":"message_stop"}

            """;

    private static final String TOOL_USE_ROUND = """
            data: {"type":"message_start","message":{"id":"msg_t1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"calculator","input":{}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"expression\\":\\"2+2\\"}"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":15,"output_tokens":8}}

            data: {"type":"message_stop"}

            """;

    private static final String FINAL_TEXT_ROUND = """
            data: {"type":"message_start","message":{"id":"msg_t2"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"4"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":20,"output_tokens":1}}

            data: {"type":"message_stop"}

            """;

    @Test
    void streamForwardsTextDeltasAndCompletes() {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), "You are helpful",
                "Hi", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));
        assertTrue(session.isClosed(), "session must complete after final round");
        assertEquals("Bonjour Atmosphere", session.text());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamRunsToolLoopUntilFinalText() throws Exception {
        var httpClient = mockTwoRoundResponse(TOOL_USE_ROUND, FINAL_TEXT_ROUND);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var calls = new AtomicInteger();
        var calculator = ToolDefinition.builder("calculator", "Evaluate an expression")
                .parameter("expression", "math expression", "string")
                .executor(args -> {
                    calls.incrementAndGet();
                    return "4";
                })
                .build();
        var context = new AgentExecutionContext(
                "What is 2+2?", "You are helpful", "claude-sonnet-4-6",
                null, "session-tool", "user-1", "conv-tool",
                List.of(calculator), null, null, List.of(), Map.of(),
                List.of(), null, null);
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), context.systemPrompt(),
                context.message(), context, session, null);
        session.await(java.time.Duration.ofSeconds(5));
        assertEquals(1, calls.get(), "tool executor must run exactly once");
        assertEquals("4", session.text(), "session must carry the final text");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void streamReportsSessionErrorOnNon2xx() {
        var httpClient = mockSingleResponse(500, "{\"error\":\"server boom\"}");
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), null,
                "Hi", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));
        assertTrue(session.failed(), "non-2xx must surface as session.error()");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customHeadersReachTheWire() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = AnthropicMessagesClient.builder()
                .apiKey("real-key")
                .httpClient(httpClient)
                .customHeader("Helicone-Auth", "sk-h-xyz")
                .customHeader("X-Tenant-Id", "tenant-3")
                // Reserved — must be filtered out so apiKey remains authoritative
                .customHeader("x-api-key", "attacker-key")
                .build();
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), null, "Hi",
                textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        var sent = captor.getValue();
        assertEquals(List.of("sk-h-xyz"), sent.headers().allValues("Helicone-Auth"));
        assertEquals(List.of("tenant-3"), sent.headers().allValues("X-Tenant-Id"));
        assertEquals(List.of("real-key"), sent.headers().allValues("x-api-key"),
                "x-api-key from customHeaders must be filtered; apiKey value wins");
        assertEquals(List.of("2023-06-01"), sent.headers().allValues("anthropic-version"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestBodyCarriesRequiredFields() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), "be terse",
                "ping", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        var bodyPublisher = captor.getValue().bodyPublisher().orElseThrow();
        // Drain the publisher into a string so the request shape is asserted
        // on the actual wire payload, not on our intermediate object tree.
        var body = drainBody(bodyPublisher);
        assertTrue(body.contains("\"model\":\"claude-sonnet-4-6\""), body);
        assertTrue(body.contains("\"max_tokens\""), body);
        assertTrue(body.contains("\"stream\":true"), body);
        assertTrue(body.contains("\"system\":\"be terse\""), body);
        assertTrue(body.contains("\"role\":\"user\""), body);
        assertTrue(body.contains("\"text\":\"ping\""), body);
    }

    private static String drainBody(java.net.http.HttpRequest.BodyPublisher publisher) {
        var collector = new java.util.concurrent.atomic.AtomicReference<String>();
        var done = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private final StringBuilder buf = new StringBuilder();

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                var bytes = new byte[item.remaining()];
                item.get(bytes);
                buf.append(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                collector.set(buf.toString());
                done.countDown();
            }
        });
        try {
            done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        var body = collector.get();
        assertNotNull(body, "request body must drain to a string");
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void visionPartsTranslateToImageBlock() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var imageBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic
        var context = new AgentExecutionContext(
                "What is in this image?", null, "claude-sonnet-4-6",
                null, "session-vis", "user-1", "conv-vis",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(),
                List.of(org.atmosphere.ai.Content.image(imageBytes, "image/png")),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
        var session = new CollectingSession();
        client.stream("claude-sonnet-4-6", List.of(), null, context.message(),
                context, session, null);
        session.await(java.time.Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        var body = drainBody(captor.getValue().bodyPublisher().orElseThrow());
        // Wire shape: image block with base64 source. Asserts the runtime
        // actually built a content array, not just an empty user message.
        assertTrue(body.contains("\"type\":\"image\""), body);
        assertTrue(body.contains("\"media_type\":\"image/png\""), body);
        assertTrue(body.contains("\"type\":\"base64\""), body);
        // PNG magic bytes 89 50 4E 47 base64-encode to "iVBORw==" (4 bytes
        // padded with two ='s). Pin the exact encoding so a future Base64
        // helper swap doesn't silently change the wire payload.
        assertTrue(body.contains("\"data\":\"iVBORw==\""), body);
        assertTrue(body.contains("\"text\":\"What is in this image?\""), body);
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hi", "You are helpful", "claude-sonnet-4-6",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockSingleResponse(int statusCode, String body) {
        try {
            var httpClient = mock(HttpClient.class);
            var response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(statusCode);
            when(response.body()).thenReturn(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);
            return httpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockTwoRoundResponse(String firstBody, String secondBody) throws Exception {
        var httpClient = mock(HttpClient.class);
        var first = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200);
        when(first.body()).thenReturn(
                new ByteArrayInputStream(firstBody.getBytes(StandardCharsets.UTF_8)));
        var second = mock(HttpResponse.class);
        when(second.statusCode()).thenReturn(200);
        when(second.body()).thenReturn(
                new ByteArrayInputStream(secondBody.getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(first, second);
        return httpClient;
    }
}
