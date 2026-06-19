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
package org.atmosphere.ai.cohere;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.llm.ToolLoopPolicies;
import org.atmosphere.ai.llm.ToolLoopPolicy;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the Cohere v2 Chat client: SSE text streaming,
 * tool-call round-trip, custom-header passthrough, and non-2xx error
 * propagation. Mocks {@link HttpClient} so every assertion runs without
 * touching the network.
 */
class CohereChatClientTest {

    private static final String TEXT_RESPONSE = """
            data: {"type":"message-start","id":"msg_1"}

            data: {"type":"content-start","index":0}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"Bonjour"}}}}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":" Atmosphere"}}}}

            data: {"type":"content-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"tokens":{"input_tokens":7,"output_tokens":2}}}}

            """;

    private static final String TOOL_USE_ROUND = """
            data: {"type":"message-start","id":"msg_t1"}

            data: {"type":"tool-call-start","index":0,"delta":{"message":{"tool_calls":{"id":"call_1","type":"function","function":{"name":"calculator","arguments":""}}}}}

            data: {"type":"tool-call-delta","index":0,"delta":{"message":{"tool_calls":{"function":{"arguments":"{\\"expression\\":\\"2+2\\"}"}}}}}

            data: {"type":"tool-call-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"TOOL_CALL","usage":{"tokens":{"input_tokens":15,"output_tokens":8}}}}

            """;

    private static final String FINAL_TEXT_ROUND = """
            data: {"type":"message-start","id":"msg_t2"}

            data: {"type":"content-start","index":0}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"4"}}}}

            data: {"type":"content-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"tokens":{"input_tokens":20,"output_tokens":1}}}}

            """;

    /**
     * Three-fragment {@code tool-call-delta} stream. Mirrors the wire shape
     * Cohere emits when {@code function.arguments} is too large to fit in a
     * single SSE frame — the model streams the JSON in chunks. Pinning all
     * three so the test fails if any one chunk is dropped or coalesced
     * (Correctness Invariant #7 — Mode Parity with BuiltInAgentRuntime's
     * OpenAI chat-completions delta path).
     */
    private static final String TOOL_DELTA_ROUND = """
            data: {"type":"message-start","id":"msg_d1"}

            data: {"type":"tool-call-start","index":0,"delta":{"message":{"tool_calls":{"id":"call_42","type":"function","function":{"name":"search","arguments":""}}}}}

            data: {"type":"tool-call-delta","index":0,"delta":{"message":{"tool_calls":{"function":{"arguments":"{\\"q"}}}}}

            data: {"type":"tool-call-delta","index":0,"delta":{"message":{"tool_calls":{"function":{"arguments":"uery\\":\\"at"}}}}}

            data: {"type":"tool-call-delta","index":0,"delta":{"message":{"tool_calls":{"function":{"arguments":"mos\\"}"}}}}}

            data: {"type":"tool-call-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"TOOL_CALL","usage":{"tokens":{"input_tokens":20,"output_tokens":4}}}}

            """;

    private static final String FINAL_AFTER_DELTA = """
            data: {"type":"message-start","id":"msg_d2"}

            data: {"type":"content-start","index":0}

            data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"done"}}}}

            data: {"type":"content-end","index":0}

            data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"tokens":{"input_tokens":25,"output_tokens":1}}}}

            """;

    @Test
    void streamForwardsTextDeltasAndCompletes() {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), "You are helpful",
                "Hi", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));
        assertTrue(session.isClosed(), "session must complete after final round");
        assertEquals("Bonjour Atmosphere", session.text());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamRunsToolLoopUntilFinalText() throws Exception {
        var httpClient = mockTwoRoundResponse(TOOL_USE_ROUND, FINAL_TEXT_ROUND);
        var client = CohereChatClient.builder()
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
                "What is 2+2?", "You are helpful", "command-a-plus-05-2026",
                null, "session-tool", "user-1", "conv-tool",
                List.of(calculator), null, null, List.of(), Map.of(),
                List.of(), null, null);
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), context.systemPrompt(),
                context.message(), context, session, null);
        session.await(java.time.Duration.ofSeconds(5));
        assertEquals(1, calls.get(), "tool executor must run exactly once");
        assertEquals("4", session.text(), "session must carry the final text");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void streamReportsSessionErrorOnNon2xx() {
        var httpClient = mockSingleResponse(500, "{\"error\":\"server boom\"}");
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), null,
                "Hi", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));
        assertTrue(session.failed(), "non-2xx must surface as session.error()");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customHeadersReachTheWire() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = CohereChatClient.builder()
                .apiKey("real-key")
                .httpClient(httpClient)
                .customHeader("Helicone-Auth", "sk-h-xyz")
                .customHeader("X-Tenant-Id", "tenant-3")
                // Reserved — must be filtered out so apiKey remains authoritative.
                .customHeader("authorization", "Bearer attacker-key")
                .build();
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), null, "Hi",
                textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        var sent = captor.getValue();
        assertEquals(List.of("sk-h-xyz"), sent.headers().allValues("Helicone-Auth"));
        assertEquals(List.of("tenant-3"), sent.headers().allValues("X-Tenant-Id"));
        assertEquals(List.of("Bearer real-key"), sent.headers().allValues("authorization"),
                "authorization from customHeaders must be filtered; apiKey value wins");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestBodyCarriesRequiredFields() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), "be terse",
                "ping", textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        var bodyPublisher = captor.getValue().bodyPublisher().orElseThrow();
        var body = drainBody(bodyPublisher);
        assertTrue(body.contains("\"model\":\"command-a-plus-05-2026\""), body);
        assertTrue(body.contains("\"max_tokens\""), body);
        assertTrue(body.contains("\"stream\":true"), body);
        assertTrue(body.contains("\"role\":\"system\""), body);
        assertTrue(body.contains("\"content\":\"be terse\""), body);
        assertTrue(body.contains("\"role\":\"user\""), body);
        assertTrue(body.contains("\"content\":\"ping\""), body);
    }

    @Test
    @SuppressWarnings("unchecked")
    void baseUrlOverridePointsAtSovereignEndpoint() throws Exception {
        // Command A+ is intended for self-hosted deployment — verify the base
        // URL override actually reaches the wire so the BYO-endpoint promise
        // in the sovereign-deploy sample is not vaporware.
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .baseUrl("https://command-a-plus.internal.example.com")
                .httpClient(httpClient)
                .build();
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), null, "ping",
                textContext(), session, null);
        session.await(java.time.Duration.ofSeconds(5));
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(
                "https://command-a-plus.internal.example.com/v2/chat",
                captor.getValue().uri().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolCallDeltaFragmentsDispatchedToSession() throws Exception {
        // Cohere streams large {@code function.arguments} in multiple
        // {@code tool-call-delta} events. Pin that every fragment forwards
        // intact (and in order) to {@link StreamingSession#toolCallDelta} so
        // browser UIs can render partial tool-argument JSON before the
        // consolidated {@link org.atmosphere.ai.AiEvent.ToolStart} frame
        // fires — same wire posture as BuiltInAgentRuntime's OpenAI chat-
        // completions loop (Correctness Invariant #7 — Mode Parity).
        var httpClient = mockTwoRoundResponse(TOOL_DELTA_ROUND, FINAL_AFTER_DELTA);
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var search = ToolDefinition.builder("search", "look something up")
                .parameter("query", "search query", "string")
                .executor(args -> "ok")
                .build();
        var context = new AgentExecutionContext(
                "lookup", "You are helpful", "command-a-plus-05-2026",
                null, "session-delta", "user-1", "conv-delta",
                List.of(search), null, null, List.of(), Map.of(),
                List.of(), null, null);
        var session = new ToolDeltaRecordingSession();
        client.stream("command-a-plus-05-2026", List.of(), context.systemPrompt(),
                context.message(), context, session, null);
        session.await(java.time.Duration.ofSeconds(5));
        assertEquals(null, session.failure(),
                "session must complete without an error");

        // Three tool-call-delta events → three session.toolCallDelta(...) calls,
        // each carrying the same tool-call id and the original fragment text.
        assertEquals(3, session.deltaFragments.size(),
                "every tool-call-delta SSE event must surface as one "
                        + "session.toolCallDelta call");
        assertEquals("call_42", session.deltaIds.get(0));
        assertEquals("call_42", session.deltaIds.get(1));
        assertEquals("call_42", session.deltaIds.get(2));
        assertEquals("{\"q", session.deltaFragments.get(0));
        assertEquals("uery\":\"at", session.deltaFragments.get(1));
        assertEquals("mos\"}", session.deltaFragments.get(2));
        // The accumulator must still reconstruct the full JSON for tool
        // dispatch — sanity-check via the final text round.
        assertEquals("done", session.text());
    }

    @Test
    @SuppressWarnings("unchecked")
    void visionPartsTranslateToImageUrlBlock() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_RESPONSE);
        var client = CohereChatClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
        var imageBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic
        var context = new AgentExecutionContext(
                "What is in this image?", null, "command-a-vision-07-2025",
                null, "session-vis", "user-1", "conv-vis",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(),
                List.of(org.atmosphere.ai.Content.image(imageBytes, "image/png")),
                org.atmosphere.ai.approval.ToolApprovalPolicy.annotated());
        var session = new CollectingSession();
        client.stream("command-a-vision-07-2025", List.of(), null, context.message(),
                context, session, null);
        session.await(java.time.Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        var body = drainBody(captor.getValue().bodyPublisher().orElseThrow());
        // Wire shape: OpenAI-compatible image_url with data: URI.
        // Pin both the literal "image_url" type discriminator and the
        // data-URI prefix so a future refactor cannot silently change the
        // payload to a non-Cohere shape.
        assertTrue(body.contains("\"type\":\"image_url\""), body);
        assertTrue(body.contains("\"url\":\"data:image/png;base64,iVBORw==\""), body);
        assertTrue(body.contains("\"type\":\"text\""), body);
        assertTrue(body.contains("\"text\":\"What is in this image?\""), body);
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
    void strictPolicyFailsAfterCapWithToolLoopExhausted() throws Exception {
        var httpClient = mockAlwaysToolResponse();
        var client = CohereChatClient.builder()
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
        var context = ToolLoopPolicies.attach(toolContext(calculator), ToolLoopPolicy.strict(2));
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), context.systemPrompt(),
                context.message(), context, session, null);
        session.await(java.time.Duration.ofSeconds(5));

        assertTrue(session.failed(), "strict(2) must surface the cap hit as session.error()");
        assertInstanceOf(ToolLoopPolicy.ToolLoopExhaustedException.class, session.failure(),
                "FAIL overflow must carry ToolLoopExhaustedException");
        assertEquals(2, ((ToolLoopPolicy.ToolLoopExhaustedException) session.failure()).maxIterations(),
                "the exhausted-exception must report the configured cap");
        assertEquals(2, calls.get(),
                "tool executor must run exactly the cap (2) times, not the hardcoded 5");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void completePolicyStopsAtCapAndCompletes() throws Exception {
        var httpClient = mockAlwaysToolResponse();
        var client = CohereChatClient.builder()
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
        var context = ToolLoopPolicies.attach(toolContext(calculator), ToolLoopPolicy.maxIterations(2));
        var session = new CollectingSession();
        client.stream("command-a-plus-05-2026", List.of(), context.systemPrompt(),
                context.message(), context, session, null);
        session.await(java.time.Duration.ofSeconds(5));

        assertTrue(session.isClosed(), "maxIterations(2) COMPLETE mode must complete the session");
        assertFalse(session.failed(), "COMPLETE overflow must NOT surface an error");
        assertEquals(2, calls.get(),
                "tool executor must run exactly the cap (2) times, not the hardcoded 5");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultPolicyCapsAtFiveAndCompletes() throws Exception {
        var httpClient = mockAlwaysToolResponse();
        var client = CohereChatClient.builder()
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
        // No policy attached → ToolLoopPolicy.DEFAULT (5, COMPLETE_WITHOUT_TOOLS).
        var session = new CollectingSession();
        var context = toolContext(calculator);
        client.stream("command-a-plus-05-2026", List.of(), context.systemPrompt(),
                context.message(), context, session, null);
        session.await(java.time.Duration.ofSeconds(5));

        assertTrue(session.isClosed(), "default policy must complete the session on overflow");
        assertFalse(session.failed(), "default overflow is COMPLETE_WITHOUT_TOOLS, not an error");
        assertEquals(5, calls.get(),
                "default cap (5) must hold when no policy is attached");
        verify(httpClient, times(5)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hi", "You are helpful", "command-a-plus-05-2026",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    private static AgentExecutionContext toolContext(ToolDefinition tool) {
        return new AgentExecutionContext(
                "What is 2+2?", "You are helpful", "command-a-plus-05-2026",
                null, "session-tool", "user-1", "conv-tool",
                List.of(tool), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /**
     * Regression scaffolding for the tool-loop cap fix (Correctness Invariant
     * #7, Mode Parity): a model that ALWAYS emits a tool-call round, so the
     * loop only terminates when the {@link ToolLoopPolicy} cap fires. Every
     * {@code send} returns a fresh response with a fresh body stream, because
     * each round re-reads {@code response.body()} and a {@link ByteArrayInputStream}
     * is single-use.
     */
    @SuppressWarnings("unchecked")
    private static HttpClient mockAlwaysToolResponse() throws Exception {
        var httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    var response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.body()).thenReturn(new ByteArrayInputStream(
                            TOOL_USE_ROUND.getBytes(StandardCharsets.UTF_8)));
                    return response;
                });
        return httpClient;
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

    /**
     * Minimal {@link org.atmosphere.ai.StreamingSession} that records every
     * {@code toolCallDelta(id, chunk)} call so the
     * {@code tool-call-delta} → {@code session.toolCallDelta} mapping can be
     * asserted with order-preserving fidelity. {@link CollectingSession} is
     * {@code final} so we cannot extend it — re-implementing the surface here
     * keeps the recording slot independent of the production session.
     */
    private static final class ToolDeltaRecordingSession
            implements org.atmosphere.ai.StreamingSession {
        final java.util.List<String> deltaIds =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.List<String> deltaFragments =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final StringBuffer buffer = new StringBuffer();
        private final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        private volatile boolean closed;
        private volatile Throwable failure;
        private final String sessionId = "tool-delta-recording-" + System.nanoTime();

        @Override
        public String sessionId() { return sessionId; }

        @Override
        public void send(String text) {
            if (text != null) {
                buffer.append(text);
            }
        }

        @Override
        public void sendMetadata(String key, Object value) { /* no-op */ }

        @Override
        public void toolCallDelta(String toolCallId, String argsChunk) {
            if (toolCallId == null || argsChunk == null || argsChunk.isEmpty()) {
                return;
            }
            deltaIds.add(toolCallId);
            deltaFragments.add(argsChunk);
        }

        @Override
        public void progress(String message) { /* no-op */ }

        @Override
        public void complete() {
            if (!closed) {
                closed = true;
                latch.countDown();
            }
        }

        @Override
        public void complete(String summary) {
            if (summary != null) {
                buffer.append(summary);
            }
            complete();
        }

        @Override
        public void error(Throwable t) {
            if (!closed) {
                closed = true;
                failure = t;
                latch.countDown();
            }
        }

        @Override
        public boolean isClosed() { return closed; }

        void await(java.time.Duration timeout) {
            try {
                latch.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() { return buffer.toString(); }

        Throwable failure() { return failure; }
    }
}
