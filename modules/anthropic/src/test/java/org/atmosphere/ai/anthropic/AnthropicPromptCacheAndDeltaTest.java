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
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wire-shape tests for the two Anthropic capabilities added alongside
 * {@code AiCapability.PROMPT_CACHING} and {@code AiCapability.TOOL_CALL_DELTA}:
 *
 * <ol>
 *   <li>{@code cache_control} breakpoint emission driven by the portable
 *       {@link CacheHint} — the system prompt switches to Anthropic's block
 *       form and the last tool definition carries a second breakpoint — plus
 *       the round-trip proof that {@code cache_read_input_tokens} reaches
 *       {@link TokenUsage#cachedInput()}.</li>
 *   <li>{@code input_json_delta} fragments forwarded to
 *       {@link StreamingSession#toolCallDelta(String, String)}.</li>
 * </ol>
 */
class AnthropicPromptCacheAndDeltaTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Final round: plain text, and a usage block carrying cache_read tokens. */
    private static final String TEXT_WITH_CACHE_READ = """
            data: {"type":"message_start","message":{"id":"msg_1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Cached"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":11,"output_tokens":2,"cache_read_input_tokens":2048}}

            data: {"type":"message_stop"}

            """;

    /**
     * A tool_use block whose arguments arrive as THREE separate
     * input_json_delta frames — the shape that distinguishes real delta
     * forwarding from a single consolidated emission.
     */
    private static final String TOOL_USE_SPLIT_DELTAS = """
            data: {"type":"message_start","message":{"id":"msg_t1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_abc","name":"calculator","input":{}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"expre"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"ssion\\":\\"2"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"+2\\"}"}}

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

    // ---------------------------------------------------------------- caching

    @Test
    @SuppressWarnings("unchecked")
    void cacheHintEmitsCacheControlOnSystemAndLastTool() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_WITH_CACHE_READ);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key").httpClient(httpClient).build();
        var session = new CapturingSession();

        var context = contextWith(CacheHint.conservative("tenant-7"),
                List.of(toolDef("alpha"), toolDef("omega")));
        client.stream("claude-sonnet-4-6", List.of(), "You are a long stable prompt",
                "Hi", context, session, null);
        session.await();

        var body = MAPPER.readTree(capturedBody(httpClient));

        // System prompt must be the ARRAY form — only a block can carry
        // cache_control — with the ephemeral marker on the (last) block.
        var system = body.get("system");
        assertTrue(system.isArray(), "system must be the block form when caching: " + system);
        assertEquals(1, system.size());
        var systemBlock = system.get(0);
        assertEquals("text", systemBlock.get("type").asString(""));
        assertEquals("You are a long stable prompt", systemBlock.get("text").asString(""));
        assertEquals("ephemeral",
                systemBlock.path("cache_control").path("type").asString(""),
                "system block must carry cache_control: " + systemBlock);
        assertTrue(systemBlock.path("cache_control").path("ttl").isMissingNode(),
                "CONSERVATIVE must use the default 5m TTL (no ttl field): " + systemBlock);

        // Second breakpoint on the LAST tool definition only.
        var tools = body.get("tools");
        assertEquals(2, tools.size());
        assertTrue(tools.get(0).path("cache_control").isMissingNode(),
                "only the last tool carries the breakpoint: " + tools.get(0));
        assertEquals("ephemeral",
                tools.get(1).path("cache_control").path("type").asString(""),
                "last tool must carry cache_control: " + tools.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggressiveHintRequestsExtendedOneHourTtl() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_WITH_CACHE_READ);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key").httpClient(httpClient).build();
        var session = new CapturingSession();

        client.stream("claude-sonnet-4-6", List.of(), "stable prompt", "Hi",
                contextWith(CacheHint.aggressive("tenant-7"), List.of()), session, null);
        session.await();

        var body = MAPPER.readTree(capturedBody(httpClient));
        assertEquals("1h",
                body.path("system").get(0).path("cache_control").path("ttl").asString(""),
                "AGGRESSIVE must request the 1h extended cache lifetime: " + body.get("system"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitOneHourTtlHintRequestsExtendedTtl() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_WITH_CACHE_READ);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key").httpClient(httpClient).build();
        var session = new CapturingSession();

        var hint = new CacheHint(CacheHint.CachePolicy.CONSERVATIVE,
                Optional.of("k"), Optional.of(Duration.ofHours(2)));
        client.stream("claude-sonnet-4-6", List.of(), "stable prompt", "Hi",
                contextWith(hint, List.of()), session, null);
        session.await();

        var body = MAPPER.readTree(capturedBody(httpClient));
        assertEquals("1h",
                body.path("system").get(0).path("cache_control").path("ttl").asString(""),
                "a >=1h TTL hint must request the extended lifetime");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noCacheHintKeepsLegacyPlainStringSystemAndNoBreakpoints() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_WITH_CACHE_READ);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key").httpClient(httpClient).build();
        var session = new CapturingSession();

        // No CacheHint in metadata — the opt-out path must stay byte-identical
        // to the pre-caching wire shape.
        client.stream("claude-sonnet-4-6", List.of(), "be terse", "Hi",
                contextWith(null, List.of(toolDef("alpha"))), session, null);
        session.await();

        var body = MAPPER.readTree(capturedBody(httpClient));
        assertTrue(body.get("system").isString(),
                "system must stay a plain string without a hint: " + body.get("system"));
        assertEquals("be terse", body.get("system").asString(""));
        assertTrue(body.get("tools").get(0).path("cache_control").isMissingNode(),
                "no breakpoints without a hint: " + body.get("tools"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheReadTokensFlowToTokenUsageCachedInput() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_WITH_CACHE_READ);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key").httpClient(httpClient).build();
        var session = new CapturingSession();

        client.stream("claude-sonnet-4-6", List.of(), "stable prompt", "Hi",
                contextWith(CacheHint.conservative("tenant-7"), List.of()), session, null);
        session.await();

        // The saving the cache_control breakpoints buy is observable end to
        // end: message_delta.usage.cache_read_input_tokens becomes
        // TokenUsage.cachedInput.
        var usage = session.usage.get();
        assertNotNull(usage, "a usage record must reach the session");
        assertEquals(2048L, usage.cachedInput(),
                "cache_read_input_tokens must land on TokenUsage.cachedInput");
        assertEquals(11L, usage.input());
        assertEquals(2L, usage.output());
    }

    // ------------------------------------------------------------ tool deltas

    @Test
    @SuppressWarnings("unchecked")
    void inputJsonDeltaFragmentsForwardToToolCallDelta() throws Exception {
        var httpClient = mockTwoRoundResponse(TOOL_USE_SPLIT_DELTAS, FINAL_TEXT_ROUND);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key").httpClient(httpClient).build();
        var session = new CapturingSession();
        var calculator = ToolDefinition.builder("calculator", "Evaluate an expression")
                .parameter("expression", "math expression", "string")
                .executor(args -> "4")
                .build();

        client.stream("claude-sonnet-4-6", List.of(), "You are helpful", "What is 2+2?",
                contextWith(null, List.of(calculator)), session, null);
        session.await();

        // Every fragment is forwarded individually, in wire order, keyed by the
        // tool-call id from content_block_start — not one consolidated frame.
        assertEquals(
                List.of("toolu_abc={\"expre", "toolu_abc=ssion\":\"2", "toolu_abc=+2\"}"),
                session.toolDeltas,
                "each input_json_delta fragment must reach session.toolCallDelta");
        // And the accumulated arguments still parse into the executed tool call.
        assertTrue(session.toolStarts.contains("calculator"),
                "the consolidated ToolStart frame must still fire: " + session.toolStarts);
        assertEquals("4", session.text.toString(), "final round text must reach the session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void textOnlyStreamEmitsNoToolCallDeltas() throws Exception {
        var httpClient = mockSingleResponse(200, TEXT_WITH_CACHE_READ);
        var client = AnthropicMessagesClient.builder()
                .apiKey("test-key").httpClient(httpClient).build();
        var session = new CapturingSession();

        client.stream("claude-sonnet-4-6", List.of(), null, "Hi",
                contextWith(null, List.of()), session, null);
        session.await();

        assertTrue(session.toolDeltas.isEmpty(),
                "a text-only stream must emit no delta frames: " + session.toolDeltas);
    }

    // ---------------------------------------------------------------- helpers

    private static ToolDefinition toolDef(String name) {
        return ToolDefinition.builder(name, "desc for " + name)
                .parameter("q", "a param", "string")
                .executor(args -> "ok")
                .build();
    }

    private static AgentExecutionContext contextWith(CacheHint hint, List<ToolDefinition> tools) {
        Map<String, Object> metadata = hint == null
                ? Map.of() : Map.of(CacheHint.METADATA_KEY, hint);
        return new AgentExecutionContext(
                "Hi", "You are helpful", "claude-sonnet-4-6",
                null, "session-1", "user-1", "conv-1",
                tools, null, null, List.of(), metadata,
                List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static String capturedBody(HttpClient httpClient) throws Exception {
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return drainBody(captor.getValue().bodyPublisher().orElseThrow());
    }

    private static String drainBody(HttpRequest.BodyPublisher publisher) {
        var collector = new AtomicReference<String>();
        var done = new CountDownLatch(1);
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
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        var body = collector.get();
        assertNotNull(body, "request body must drain to a string");
        return body;
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockSingleResponse(int status, String body) {
        try {
            var httpClient = mock(HttpClient.class);
            var response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenAnswer(inv -> new ByteArrayInputStream(
                    body.getBytes(StandardCharsets.UTF_8)));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);
            return httpClient;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockTwoRoundResponse(String first, String second) {
        try {
            var httpClient = mock(HttpClient.class);
            var bodies = new java.util.ArrayDeque<>(List.of(first, second));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenAnswer(inv -> {
                        var response = mock(HttpResponse.class);
                        var payload = bodies.isEmpty() ? second : bodies.poll();
                        when(response.statusCode()).thenReturn(200);
                        when(response.body()).thenReturn(new ByteArrayInputStream(
                                payload.getBytes(StandardCharsets.UTF_8)));
                        return response;
                    });
            return httpClient;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Session that records the two signals under test — {@code toolCallDelta}
     * frames and the typed {@link TokenUsage} — which
     * {@code CollectingSession} routes to a no-op {@code sendMetadata}.
     */
    private static final class CapturingSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final List<String> toolDeltas = new ArrayList<>();
        private final List<String> toolStarts = new ArrayList<>();
        private final AtomicReference<TokenUsage> usage = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean closed;

        @Override public String sessionId() { return "test-session"; }

        @Override public void send(String t) {
            if (t != null) {
                text.append(t);
            }
        }

        @Override public void sendMetadata(String key, Object value) { }

        @Override public void progress(String message) { }

        @Override public void usage(TokenUsage u) { usage.set(u); }

        @Override public void toolCallDelta(String toolCallId, String argsChunk) {
            if (toolCallId != null && argsChunk != null && !argsChunk.isEmpty()) {
                toolDeltas.add(toolCallId + "=" + argsChunk);
            }
        }

        @Override public void emit(AiEvent event) {
            if (event instanceof AiEvent.ToolStart start) {
                toolStarts.add(start.toolName());
            }
        }

        @Override public Map<Class<?>, Object> injectables() { return new LinkedHashMap<>(); }

        @Override public void complete() { closed = true; done.countDown(); }

        @Override public void complete(String summary) { complete(); }

        @Override public void error(Throwable t) { closed = true; done.countDown(); }

        @Override public boolean isClosed() { return closed; }

        void await() {
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS), "stream must settle");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                assertFalse(true, "interrupted while awaiting stream");
            }
        }
    }
}
