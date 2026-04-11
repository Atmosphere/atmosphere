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

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.RetryPolicy;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wave-6 F-6.1 behavior verification: prove that the per-request
 * {@link RetryPolicy} override set on {@link ChatCompletionRequest#retryPolicy()}
 * actually controls the retry-loop count inside
 * {@link OpenAiCompatibleClient#sendWithRetry}. Complements the SPI-plumbing
 * assertions in {@link OpenAiCompatibleClientRetryPolicyTest} — those prove
 * the field exists and travels through the shim chain; this test proves the
 * {@code effectivePolicy = override != null ? override : retryPolicy} branch
 * inside {@code sendWithRetry} fires with the overridden value.
 *
 * <p>Spins up a local {@link HttpServer} that returns HTTP 500 on every call
 * and counts invocations via an {@link AtomicInteger}. The OpenAI-compatible
 * client sees the 500s as retryable {@code server_error}s. The invocation
 * count then maps 1:1 to the effective policy's {@code maxRetries + 1}
 * (attempts = 1 initial + N retries).</p>
 *
 * <p>Uses tiny initial delays (10ms) so the full 4-attempt default retry
 * loop completes in under a second — not worth cranking {@link
 * RetryPolicy#DEFAULT}'s 1s-2x-exponential backoff for a unit test.</p>
 */
class OpenAiCompatibleClientRetryBehaviorTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger invocationCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        invocationCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            invocationCount.incrementAndGet();
            var body = "{\"error\":{\"message\":\"simulated server error\"}}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length());
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retryPolicyNoneOverrideResultsInSingleAttempt() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey("test-key")
                .retryPolicy(fastPolicy(3))   // client default: 3 retries
                .build();

        var request = ChatCompletionRequest.builder("gpt-4")
                .message(new ChatMessage("user", "hello"))
                .retryPolicy(RetryPolicy.NONE)   // per-request override: 0 retries
                .build();

        client.streamChatCompletion(request, new SinkSession());

        assertEquals(1, invocationCount.get(),
                "RetryPolicy.NONE override must cap attempts at 1 (initial, no retries)");
    }

    @Test
    void absentOverrideFallsThroughToClientLevelPolicy() {
        // Client has a fast 3-retry policy; request has no override. The
        // effective policy must be the client's, so we expect 4 invocations.
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey("test-key")
                .retryPolicy(fastPolicy(3))
                .build();

        var request = ChatCompletionRequest.builder("gpt-4")
                .message(new ChatMessage("user", "hello"))
                .build();

        client.streamChatCompletion(request, new SinkSession());

        assertEquals(4, invocationCount.get(),
                "Absent override must fall through to client-level retryPolicy (3 retries = 4 attempts)");
    }

    @Test
    void explicitOverrideWinsOverClientLevelPolicy() {
        // Client has a zero-retry policy; request explicitly overrides with 2
        // retries. The effective policy must be the request's, so we expect
        // 3 invocations.
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey("test-key")
                .retryPolicy(RetryPolicy.NONE)
                .build();

        var request = ChatCompletionRequest.builder("gpt-4")
                .message(new ChatMessage("user", "hello"))
                .retryPolicy(fastPolicy(2))
                .build();

        client.streamChatCompletion(request, new SinkSession());

        assertEquals(3, invocationCount.get(),
                "Explicit per-request override must win (2 retries = 3 attempts)");
    }

    private static RetryPolicy fastPolicy(int maxRetries) {
        return new RetryPolicy(maxRetries,
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                2.0,
                java.util.Set.of("rate_limit", "timeout", "server_error", "unavailable"));
    }

    private static final class SinkSession implements StreamingSession {
        private volatile boolean closed;
        @Override public String sessionId() { return "retry-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { closed = true; }
        @Override public void complete(String summary) { closed = true; }
        @Override public void error(Throwable t) { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
}
